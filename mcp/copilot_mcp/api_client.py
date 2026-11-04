"""Calls the platform API as the user, and turns its errors into something a model can act on."""
from contextvars import ContextVar

import httpx
from mcp.server.mcpserver.exceptions import ToolError

from copilot_mcp.guardrails.identity import IdentityError, UserIdentity

correlation_id: ContextVar[str] = ContextVar("correlation_id", default="none")

# What a model should do next, by API error code. Upstream details are never passed through.
HINTS = {
    "not_found": "It doesn't exist, or this user can't see it. Check the ID, and don't guess others.",
    "forbidden": "This user isn't permitted to do this. Tell them. Don't try another route.",
    "validation_error": "The arguments were rejected. Fix them and retry.",
    "stale_version": "The case changed after approval. Review it again and request a new approval.",
    "case_already_escalated": "It is already escalated, so there is nothing to do.",
    "case_not_escalatable": "It is resolved or closed, so it can't be escalated.",
    "escalation_not_eligible": "P3 and P4 cases can only be escalated for Platinum or Gold.",
    "rate_limited": "Too many requests. Wait before retrying.",
    "upstream_unavailable": "A source system is down. Tell the user, and retry later.",
    "upstream_error": "A source system failed. Retry once, then tell the user.",
    "upstream_timeout": "A source system timed out. Retry once, then tell the user.",
    "idempotency_in_progress": "The same action is still running. Wait, then retry.",
}


class ApiFailure(ToolError):
    def __init__(self, code: str, retryable: bool):
        hint = HINTS.get(code, "This failed. Tell the user.")
        super().__init__(f"{code}: {hint}")
        self.code = code
        self.retryable = retryable


class PlatformApi:
    def __init__(self, http: httpx.AsyncClient, identity: UserIdentity):
        self._http = http
        self._identity = identity

    async def request(self, method, path, scopes, *, params=None, json=None, headers=None):
        try:
            token = await self._identity.token_for(*scopes)
        except IdentityError as error:
            # An exception the SDK doesn't recognise reaches the model as a bare "Error executing
            # tool", and the model needs to know what to do next.
            raise ToolError(f"{error.code}: {error.message}") from error
        try:
            response = await self._http.request(
                method,
                path,
                params=params,
                json=json,
                headers={
                    "Authorization": f"Bearer {token}",
                    "X-Correlation-Id": correlation_id.get(),
                    **(headers or {}),
                },
            )
        except httpx.HTTPError as error:
            raise ApiFailure("upstream_unavailable", True) from error
        if response.status_code >= 400:
            problem = response.json()
            raise ApiFailure(problem.get("code", "upstream_error"), problem.get("retryable", False))
        return response
