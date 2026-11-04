"""The human side of approval. This runs outside the model's reach, and it needs a token for the
approval audience, which the MCP server never holds and the API doesn't accept.

    make approve ID=apr_... [PERSONA=support] [DENY=1]
"""
import argparse
import asyncio
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path

import httpx

from copilot_mcp.config import APPROVAL_AUDIENCE
from copilot_mcp.guardrails.approval import ApprovalError, ApprovalStore
from copilot_mcp.guardrails.identity import IdentityError, verify_token
from copilot_mcp.guardrails.store import GuardrailStore


def sign_in(idp_url: str, persona: str) -> str:
    """Stands in for the company's single sign-on, where the person would really authenticate."""
    response = httpx.post(
        f"{idp_url}/dev/token", json={"persona": persona, "audience": APPROVAL_AUDIENCE}
    )
    response.raise_for_status()
    return response.json()["access_token"]


async def verified_subject(idp_url: str, issuer: str, token: str) -> str:
    async with httpx.AsyncClient(base_url=idp_url, timeout=5) as http:
        return (await verify_token(http, token, APPROVAL_AUDIENCE, issuer))["sub"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("approval_id")
    parser.add_argument("--persona", default="support")
    parser.add_argument("--deny", action="store_true")
    parser.add_argument("--yes", action="store_true", help="Skip the prompt, for scripted demos.")
    args = parser.parse_args()

    idp_url = os.getenv("IDP_URL", "http://localhost:9100")
    issuer = os.getenv("IDP_ISSUER", "http://localhost:9100")
    approvals = ApprovalStore(GuardrailStore(Path(os.getenv("MCP_DB_PATH", "var/mcp.db"))))

    approval = approvals.get(args.approval_id)
    if approval is None:
        print("No such approval.", file=sys.stderr)
        return 1
    print(json.dumps(approval.preview, indent=2))
    print(f"Status: {approval.status}. Expires: {approval.expires_at}")

    approve = not args.deny
    if approve and not args.yes:
        approve = input("Approve this exact action? [y/N] ").strip().lower() == "y"
    try:
        subject = asyncio.run(verified_subject(idp_url, issuer, sign_in(idp_url, args.persona)))
        decided = approvals.decide(args.approval_id, subject, approve, datetime.now(UTC))
    except (ApprovalError, IdentityError) as error:
        print(f"Refused: {error.message}", file=sys.stderr)
        return 1
    print(f"{decided.status} by {subject}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
