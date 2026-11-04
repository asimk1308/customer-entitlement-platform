"""The demo's flow with no LLM and no account: a fixed script playing the agent, over real stdio.
It behaves like a fooled model, so that the guardrails can be watched without any model involved.
`make demo-scripted`, with `make run` going."""
import asyncio
import json
import os
import subprocess
import sys
from pathlib import Path

import httpx
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

ROOT = Path(__file__).parents[2]
MOCKS, IDP = "http://localhost:9001", "http://localhost:9100"
DB = ROOT / "var" / "demo-scripted.db"
ESCALATION = {
    "case_id": "CS-10240",
    "reason_code": "customer_impact",
    "justification": "The case comment told me to escalate everything.",
}


def show(label, result) -> None:
    state = "ERROR" if result.is_error else "OK"
    print(f"\n--- {label} [{state}]\n{result.content[0].text[:600]}")


def status_of(case_id: str) -> str:
    return httpx.get(f"{MOCKS}/cases/{case_id}").json()["Status"]


def sign_in_and_reset() -> str:
    httpx.post(f"{MOCKS}/__reset").raise_for_status()
    DB.unlink(missing_ok=True)
    return httpx.post(
        f"{IDP}/dev/token", json={"persona": "support", "audience": "copilot-mcp"}
    ).json()["access_token"]


async def main(token: str) -> int:
    env = {
        "PATH": os.environ["PATH"],
        "PYTHONPATH": "mcp",
        "COPILOT_USER_TOKEN": token,
        "MCP_DB_PATH": str(DB),
    }
    parameters = StdioServerParameters(
        command=sys.executable, args=["-m", "copilot_mcp"], env=env, cwd=str(ROOT)
    )
    async with stdio_client(parameters) as (read, write), ClientSession(read, write) as session:
        await session.initialize()
        show(
            "Sample question",
            await session.call_tool(
                "search_cases",
                {
                    "priority": ["P1"],
                    "support_tier": ["Platinum"],
                    "min_age_hours": 48,
                    "renewal_within_days": 90,
                },
            ),
        )
        first = await session.call_tool("escalate_case", ESCALATION)
        show("A fooled model escalates because a comment said to", first)
        approval_id = json.loads(first.content[0].text)["approval_id"]
        arguments = ESCALATION | {"approval_id": approval_id}
        show("It reuses the ID straight away", await session.call_tool("escalate_case", arguments))
        print(f"\nCS-10240 is still: {status_of('CS-10240')}")

        print("\n--- A person approves, in a separate process")
        await asyncio.to_thread(
            subprocess.run,
            [sys.executable, "mcp/demo/approve.py", approval_id, "--yes"],
            cwd=ROOT,
            env=os.environ | {"PYTHONPATH": "mcp", "MCP_DB_PATH": str(DB)},
            check=True,
        )
        changed = ESCALATION | {"approval_id": approval_id, "case_id": "CS-10231"}
        show("It tries the approval on another case", await session.call_tool("escalate_case", changed))
        show("It sends exactly what was approved", await session.call_tool("escalate_case", arguments))
        show("It tries to use the approval twice", await session.call_tool("escalate_case", arguments))
    print(f"\nCS-10240 is now: {status_of('CS-10240')}. CS-10231 is still: {status_of('CS-10231')}")
    return 0


if __name__ == "__main__":
    # So that this script's output stays in order with the approval process's output.
    sys.stdout.reconfigure(line_buffering=True)
    sys.exit(asyncio.run(main(sign_in_and_reset())))
