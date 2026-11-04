"""The tool logic. Designed for what a model needs, not as a mirror of the REST resources: flat
filters, computed ages so the model does no date arithmetic, short snippets, no raw payloads."""
import json
from collections.abc import Callable
from contextvars import ContextVar
from datetime import date, datetime

from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import CallToolResult, TextContent
from pydantic import BaseModel, ConfigDict, model_serializer

from copilot_mcp.api_client import ApiFailure, PlatformApi
from copilot_mcp.guardrails.approval import ApprovalError, ApprovalStore, args_hash
from copilot_mcp.guardrails.identity import UserIdentity
from copilot_mcp.guardrails.ratelimit import RateLimiter
from copilot_mcp.guardrails.sanitize import clean, looks_like_injection

MAX_RESPONSE_BYTES = 8000
SUBJECT_LENGTH = 200
COMMENT_LENGTH = 280
STATUS_GROUPS = {
    # `escalated` belongs here: a filter on `open` alone would hide the most urgent cases.
    "active": ["open", "in_progress", "escalated"],
    "closed": ["resolved", "closed"],
    "any": [],
}
SORTS = {
    "highest_priority": "priority",
    "oldest_first": "openedAt",
    "newest_first": "-openedAt",
}
ESCALATE_SCOPE = "cases:escalate"
COMMENT_SCOPE = "cases:comment"
APPROVAL_HOW = (
    "Tell the user what you asked to do and give them this approval_id. They approve it outside "
    "this conversation. Once they confirm, call this tool again with the same arguments plus "
    "approval_id."
)

# Read by the audit middleware. Set by whichever guardrail decided what happened to a call.
decision: ContextVar[str] = ContextVar("decision", default="allowed")


class Strict(BaseModel):
    """Empty values are left out. On a 25-row page, repeating `null` and `false` on every row costs
    more than a shorter payload saves, which measuring showed. Tool descriptions say what an absent
    field means."""

    model_config = ConfigDict(extra="forbid")

    @model_serializer(mode="wrap")
    def _omit_empty(self, handler):
        return {key: value for key, value in handler(self).items() if value not in (None, [])}


class CaseRow(Strict):
    case_id: str
    account_id: str
    customer: str | None = None
    priority: str
    status: str
    age_hours: int
    support_tier: str | None = None
    days_to_renewal: int | None = None
    comment_count: int | None = None
    untrusted_subject: str
    untrusted_latest_comment: str | None = None
    injection_suspected: bool | None = None  # Only ever true, or absent.


class SearchResult(Strict):
    cases: list[CaseRow]
    has_more: bool
    next_cursor: str | None = None
    degraded_sources: list[str] = []
    notice: str | None = None


class CustomerSummary(Strict):
    account_id: str
    name: str | None = None
    support_tier: str
    renewal_date: str
    days_to_renewal: int
    arr_usd: float | None = None
    withheld_fields: list[str] = []
    usage_month: str | None = None
    protected_tb: float | None = None
    api_calls: int | None = None
    active_users: int | None = None
    degraded_sources: list[str] = []
    data_quality_issues: list[str] = []


class WriteOutcome(Strict):
    status: str
    case_id: str
    approval_id: str | None = None
    expires_at: str | None = None
    what_was_requested: dict | None = None
    next_step: str | None = None
    case_status: str | None = None
    comment_id: str | None = None


def fail(code: str, message: str) -> ToolError:
    return ToolError(f"{code}: {message}")


class CopilotTools:
    def __init__(
        self,
        api: PlatformApi,
        identity: UserIdentity,
        approvals: ApprovalStore,
        limiter: RateLimiter,
        agent: str,
        clock: Callable[[], datetime],
    ):
        self._api = api
        self._identity = identity
        self._approvals = approvals
        self._limiter = limiter
        self._agent = agent
        self._clock = clock

    def _limit(self, bucket: str) -> None:
        wait = self._limiter.try_acquire(self._agent, self._identity.subject, bucket, self._clock())
        if wait is not None:
            decision.set(f"rate_limited:{bucket}")
            raise fail(
                "agent_rate_limited",
                f"The {bucket} limit for this agent and user was reached. It resets in {wait} "
                "seconds. Don't retry before then.",
            )

    def _limit_keeping_approval(self, bucket, approval) -> None:
        """A person's approval shouldn't be lost because the agent hit a limit. It stays usable until
        it expires, and the limit still decides when it can run."""
        try:
            self._limit(bucket)
        except ToolError:
            self._approvals.restore(approval.id)
            raise

    def _case_row(self, raw: dict, now: datetime) -> CaseRow:
        renewal = raw["renewalDate"]
        latest = raw["latestComment"]
        subject = raw["subject"]
        comment = latest["body"] if latest else None
        opened = datetime.fromisoformat(raw["openedAt"])
        return CaseRow(
            case_id=raw["id"],
            account_id=raw["accountId"],
            customer=clean(raw["customerName"], 120) if raw["customerName"] else None,
            priority=raw["priority"],
            status=raw["status"],
            age_hours=int((now - opened).total_seconds() // 3600),
            support_tier=raw["supportTier"],
            days_to_renewal=(date.fromisoformat(renewal) - now.date()).days if renewal else None,
            comment_count=raw["commentCount"] or None,
            untrusted_subject=clean(subject, SUBJECT_LENGTH),
            untrusted_latest_comment=clean(comment, COMMENT_LENGTH) if comment else None,
            injection_suspected=looks_like_injection(subject + " " + (comment or "")) or None,
        )

    async def search_cases(
        self,
        priority=None,
        status_group="active",
        support_tier=None,
        min_age_hours=None,
        renewal_within_days=None,
        account_id=None,
        sort="highest_priority",
        limit=25,
        cursor=None,
        case_id=None,
    ) -> SearchResult:
        self._limit("read")
        if case_id:
            # Found in a live run: with no lookup by ID, the model listed cases hoping to find one.
            raw = await self._read_case(case_id)
            row = self._case_row(raw, self._clock())
            return SearchResult(cases=[row], has_more=False)
        params = {"sort": SORTS[sort], "limit": limit}
        filters = {
            "priority": priority,
            "status": STATUS_GROUPS[status_group],
            "supportTier": support_tier,
            "minAgeHours": min_age_hours,
            "renewalWithinDays": renewal_within_days,
            "accountId": account_id,
            "cursor": cursor,
        }
        params |= {name: value for name, value in filters.items() if value not in (None, [])}
        response = await self._api.request("GET", "/v1/cases", ["cases:read"], params=params)
        body = response.json()
        now = self._clock()
        rows = [self._case_row(raw, now) for raw in body["data"]]
        result = SearchResult(
            cases=rows,
            has_more=body["page"]["hasMore"],
            next_cursor=body["page"]["nextCursor"],
            degraded_sources=[source["source"] for source in body["degradedSources"]],
        )
        return self._fit(result)

    def _fit(self, result: SearchResult) -> SearchResult:
        """Bounds output size. Rows dropped here would be skipped by the API's cursor, so the cursor
        goes too, and the notice tells the model to narrow its search rather than miss them quietly."""
        if len(result.model_dump_json()) <= MAX_RESPONSE_BYTES:
            return result
        rows = list(result.cases)
        while rows and len(result.model_copy(update={"cases": rows}).model_dump_json()) > (
            MAX_RESPONSE_BYTES - 200
        ):
            rows.pop()
        return result.model_copy(
            update={
                "cases": rows,
                "has_more": True,
                "next_cursor": None,
                "notice": f"Only the first {len(rows)} matches fit. Narrow the filters to see more.",
            }
        )

    async def get_customer_summary(self, account_id: str) -> CustomerSummary:
        self._limit("read")
        response = await self._api.request(
            "GET",
            f"/v1/customers/{account_id}",
            # Requested only if the user could have it. The API still decides field by field.
            ["customers:read", "customers:financials:read"],
        )
        body = response.json()
        entitlement, usage = body["entitlement"], body["usage"] or {}
        renewal = entitlement["renewalDate"]
        return CustomerSummary(
            account_id=body["id"],
            name=clean(entitlement["name"], 120) if entitlement["name"] else None,
            support_tier=entitlement["supportTier"],
            renewal_date=renewal,
            days_to_renewal=(date.fromisoformat(renewal) - self._clock().date()).days,
            arr_usd=entitlement.get("arrUsd"),
            withheld_fields=[
                {"entitlement.arrUsd": "arr_usd"}.get(field, field)
                for field in body["redactedFields"]
            ],
            usage_month=usage.get("month"),
            protected_tb=usage.get("protectedTb"),
            api_calls=usage.get("apiCalls"),
            active_users=usage.get("activeUsers"),
            degraded_sources=[source["source"] for source in body["degradedSources"]],
            data_quality_issues=[issue["code"] for issue in body["dataQuality"]],
        )

    def _require_user_scope(self, scope: str) -> None:
        """Fails early, so a person is never asked to approve something that can't succeed. The API
        still enforces this; it is here for the person's sake, not for security."""
        if scope not in self._identity.scopes:
            decision.set(f"denied:missing_scope:{scope}")
            raise fail("not_permitted", "This user isn't permitted to do this. Tell them.")

    def _request_approval(self, tool, case, digest, etag, requested) -> WriteOutcome:
        self._limit("approval_request")
        preview = {
            "on_behalf_of": self._identity.subject,
            "agent": self._agent,
            "action": tool,
            # Read from the API rather than taken from the model, so a person sees the real case.
            "case": {
                "id": case["id"],
                "account": case["accountId"],
                "customer": case["customerName"],
                "priority": case["priority"],
                "status": case["status"],
                "subject": clean(case["subject"], SUBJECT_LENGTH),
                "support_tier": case["supportTier"],
            },
            "written_by_the_agent": requested,
        }
        try:
            approval = self._approvals.request(
                self._identity.subject,
                self._agent,
                tool,
                case["id"],
                digest,
                etag,
                preview,
                self._clock(),
            )
        except ApprovalError as error:
            decision.set(f"blocked:{error.code}")
            raise fail(error.code, error.message) from error
        decision.set("approval_required")
        return WriteOutcome(
            status="approval_required",
            case_id=case["id"],
            approval_id=approval.id,
            expires_at=approval.expires_at,
            what_was_requested=requested,
            next_step=APPROVAL_HOW,
        )

    def _consume(self, approval_id, tool, digest):
        try:
            approval = self._approvals.consume(
                approval_id, self._identity.subject, self._agent, tool, digest, self._clock()
            )
        except ApprovalError as error:
            decision.set(f"blocked:{error.code}")
            raise fail(error.code, error.message) from error
        decision.set("approved")
        return approval

    async def _read_case(self, case_id: str) -> dict:
        return (await self._api.request("GET", f"/v1/cases/{case_id}", ["cases:read"])).json()

    async def add_case_comment(self, case_id, body, approval_id=None) -> WriteOutcome:
        self._require_user_scope(COMMENT_SCOPE)
        requested = {"body": body}
        digest = args_hash("add_case_comment", {"case_id": case_id, **requested})
        if approval_id is None:
            case = await self._read_case(case_id)
            return self._request_approval("add_case_comment", case, digest, None, requested)

        approval = self._consume(approval_id, "add_case_comment", digest)
        self._limit_keeping_approval("comment", approval)
        try:
            response = await self._api.request(
                "POST",
                f"/v1/cases/{case_id}/comments",
                # The write scope only exists from this point, after a person has approved.
                [COMMENT_SCOPE],
                json={"body": body, "visibility": "internal"},
                headers={"Idempotency-Key": approval.id},
            )
        except ApiFailure as failure:
            if failure.retryable:
                self._approvals.restore(approval.id)
            raise
        return WriteOutcome(
            status="completed", case_id=case_id, comment_id=response.json()["id"]
        )

    async def escalate_case(
        self, case_id, reason_code, justification, approval_id=None
    ) -> WriteOutcome:
        self._require_user_scope(ESCALATE_SCOPE)
        requested = {"reason_code": reason_code, "justification": justification}
        digest = args_hash("escalate_case", {"case_id": case_id, **requested})
        if approval_id is None:
            case = await self._read_case(case_id)
            # Binding the version means the approval covers the case as the person saw it.
            return self._request_approval("escalate_case", case, digest, case["etag"], requested)

        approval = self._consume(approval_id, "escalate_case", digest)
        self._limit_keeping_approval("escalate", approval)
        try:
            response = await self._api.request(
                "POST",
                f"/v1/cases/{case_id}/escalate",
                [ESCALATE_SCOPE],
                json={"reasonCode": reason_code, "justification": justification},
                headers={"Idempotency-Key": approval.id, "If-Match": approval.etag},
            )
        except ApiFailure as failure:
            if failure.retryable:
                self._approvals.restore(approval.id)
            raise
        return WriteOutcome(
            status="completed",
            case_id=case_id,
            case_status=response.json()["case"]["status"],
        )


def tool_result(outcome: Strict) -> CallToolResult:
    """Returns compact text, since the SDK's default pretty-printing spends the model's context on
    indentation. The structured form carries the same data."""
    return CallToolResult(
        content=[TextContent(type="text", text=outcome.model_dump_json())],
        structured_content=outcome.model_dump(mode="json"),
    )


def public_args(args: dict) -> dict:
    """What the audit log keeps: identifiers and enums. Free text is covered only by the hash."""
    free_text = {"body", "justification", "cursor"}
    return {key: value for key, value in args.items() if key not in free_text}


def audit_args_json(args: dict) -> str:
    return json.dumps(public_args(args), sort_keys=True, default=str)

