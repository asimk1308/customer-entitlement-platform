"""The real MCP server through the real MCP client, over real HTTP to the Java API and identity
provider. Every test here goes through the protocol, not around it."""
from datetime import UTC, datetime, timedelta

import anyio.from_thread
import httpx
import pytest
from helpers import API_AUDIENCE, MCP_AUDIENCE, make_config
from mcp import Client

from copilot_mcp.guardrails.approval import ApprovalStore
from copilot_mcp.guardrails.store import GuardrailStore
from copilot_mcp.server import build_server

ESCALATION = {
    "case_id": "CS-10231",
    "reason_code": "sla_breach",
    "justification": "Open past the SLA for this tier.",
}


class MovableClock:
    """The MCP server's clock only. It governs approval expiry and agent rate limits, which live here."""

    def __init__(self):
        self.now = datetime.now(UTC)

    def __call__(self):
        return self.now

    def advance(self, **delta):
        self.now += timedelta(**delta)


class Upstream:
    """The mock source systems, arranged and inspected over their control endpoints."""

    def __init__(self, url):
        self._http = httpx.Client(base_url=url)

    def case(self, case_id):
        return self._http.get(f"/cases/{case_id}").raise_for_status().json()

    def cases(self):
        return self._http.get("/cases").raise_for_status().json()["records"]

    def status(self, case_id="CS-10231"):
        return self.case(case_id)["Status"]

    def statuses(self):
        return {case["id"]: case["Status"] for case in self.cases()}

    def change(self, case_id, **fields):
        self._http.post(f"/__cases/{case_id}", json=fields).raise_for_status()

    def chaos(self, upstream, **fields):
        self._http.post("/__chaos", json={"upstream": upstream, **fields}).raise_for_status()

    def clear_chaos(self):
        self._http.delete("/__chaos").raise_for_status()

    def reset(self):
        self._http.post("/__reset").raise_for_status()


class Session:
    """One agent session acting for one user."""

    def __init__(self, stack, portal, clock, db_path, persona, token):
        self.stack = stack
        self.portal = portal
        self.clock = clock
        self.db_path = db_path
        self.persona = persona
        self.server = portal.call(lambda: build_server(make_config(stack, token, db_path), clock=clock))

    def call(self, tool, **args):
        async def go():
            async with Client(self.server) as client:
                return await client.call_tool(tool, args)

        return self.portal.call(go)

    def ok(self, tool, **args):
        result = self.call(tool, **args)
        assert not result.is_error, result.content[0].text
        return result.structured_content

    def error(self, tool, **args):
        result = self.call(tool, **args)
        assert result.is_error, result.structured_content
        # The SDK prefixes tool errors. The code that follows is what callers act on.
        return result.content[0].text.removeprefix(f"Error executing tool {tool}: ")

    def list_tools(self):
        async def go():
            async with Client(self.server) as client:
                return await client.list_tools()

        return self.portal.call(go).tools

    def approvals(self):
        return ApprovalStore(GuardrailStore(self.db_path))

    def human_approves(self, approval_id, subject=None, approve=True):
        subject = subject or {
            "support": "jdoe",
            "finance": "fkhan",
            "other-support": "other",
        }.get(self.persona, self.persona)
        return self.approvals().decide(approval_id, subject, approve, self.clock())

    def audit(self):
        with GuardrailStore(self.db_path).connect() as db:
            return [dict(row) for row in db.execute("SELECT * FROM tool_audit ORDER BY id")]


@pytest.fixture(scope="session")
def portal():
    """One event loop for the session, as the SDK's and httpx's clients are bound to their loop."""
    with anyio.from_thread.start_blocking_portal() as blocking:
        yield blocking


@pytest.fixture(scope="session")
def upstream_client(stack):
    return Upstream(stack["upstream_url"])


@pytest.fixture
def upstream(upstream_client):
    return upstream_client


@pytest.fixture(autouse=True)
def fresh_upstream(upstream_client):
    upstream_client.reset()
    yield
    upstream_client.clear_chaos()


@pytest.fixture
def clock():
    return MovableClock()


@pytest.fixture
def chaos(upstream):
    return upstream.chaos


@pytest.fixture
def auth(stack):
    def headers(persona="support", audience=API_AUDIENCE, ttl=3600):
        body = {"persona": persona, "audience": audience, "ttlSeconds": ttl}
        token = httpx.post(f"{stack['idp_url']}/dev/token", json=body).json()["access_token"]
        return {"Authorization": f"Bearer {token}"}

    return headers


@pytest.fixture
def api(stack):
    return httpx.Client(base_url=stack["api_url"])


@pytest.fixture
def copilot(stack, portal, auth, clock, tmp_path):
    def build(persona="support"):
        token = auth(persona, audience=MCP_AUDIENCE)["Authorization"].split()[1]
        return Session(stack, portal, clock, tmp_path / "mcp.db", persona, token)

    return build


@pytest.fixture
def support(copilot):
    return copilot("support")


@pytest.fixture
def escalation():
    return dict(ESCALATION)
