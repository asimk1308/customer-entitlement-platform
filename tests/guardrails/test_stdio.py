"""The server as a real process over real stdio, the way an MCP client runs it."""
import os
import subprocess
from pathlib import Path

import anyio
import httpx
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

ROOT = Path(__file__).parents[2]
SAMPLE = {
    "priority": ["P1"],
    "support_tier": ["Platinum"],
    "min_age_hours": 48,
    "renewal_within_days": 90,
}


def test_folder_named_mcp_does_not_shadow_the_sdk():
    """The brief requires a folder called mcp/, which could hide the SDK package of the same name."""
    code = "import mcp, mcp.server.mcpserver; print(mcp.__file__)"
    result = subprocess.run(
        [str(ROOT / ".venv/bin/python"), "-c", code],
        cwd=ROOT,
        env=os.environ | {"PYTHONPATH": "mcp"},
        capture_output=True,
        text=True,
        check=True,
    )
    assert "site-packages" in result.stdout


def test_sample_question_and_approval_over_real_stdio(stack, tmp_path):
    token = httpx.post(
        f"{stack['idp_url']}/dev/token", json={"persona": "support", "audience": "copilot-mcp"}
    ).json()["access_token"]
    db_path = tmp_path / "mcp.db"
    env = {
        "PATH": os.environ["PATH"],
        "PYTHONPATH": "mcp",
        "COPILOT_USER_TOKEN": token,
        "PLATFORM_API_URL": stack["api_url"],
        "IDP_URL": stack["idp_url"],
        "IDP_ISSUER": stack["idp_url"],
        "MCP_DB_PATH": str(db_path),
    }
    parameters = StdioServerParameters(
        command=str(ROOT / ".venv/bin/python"), args=["-m", "copilot_mcp"], env=env, cwd=str(ROOT)
    )
    request = {
        "case_id": "CS-10231",
        "reason_code": "sla_breach",
        "justification": "Open past the SLA for this tier.",
    }

    async def scenario():
        async with (
            stdio_client(parameters) as (read, write),
            ClientSession(read, write) as session,
        ):
            await session.initialize()
            found = await session.call_tool("search_cases", SAMPLE)
            first = await session.call_tool("escalate_case", request)
            approval_id = first.structured_content["approval_id"]
            blocked = await session.call_tool(
                "escalate_case", request | {"approval_id": approval_id}
            )
            # A separate process, as a person's approval is outside the agent's session.
            approved = await anyio.run_process(
                [str(ROOT / ".venv/bin/python"), "mcp/demo/approve.py", approval_id, "--yes"],
                cwd=ROOT,
                env=env,
                check=False,
            )
            done = await session.call_tool("escalate_case", request | {"approval_id": approval_id})
            return found, blocked, approved, done

    found, blocked, approved, done = anyio.run(scenario)
    assert {row["case_id"] for row in found.structured_content["cases"]} == {
        "CS-10231",
        "CS-10240",
        "CS-10244",
    }
    assert blocked.is_error
    assert approved.returncode == 0, approved.stderr
    assert b"approved by jdoe" in approved.stdout
    assert b"Backup failing on node 3" in approved.stdout  # A person sees the real case.
    assert not done.is_error, done.content[0].text
    assert done.structured_content["case_status"] == "escalated"

