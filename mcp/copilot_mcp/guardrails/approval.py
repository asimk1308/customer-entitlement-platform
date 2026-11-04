"""Human approval for writes. The rule that makes it real: the model can never hold anything that
grants approval. It gets an ID that means nothing until a person, outside the model's reach and
authenticated as the same user, approves that exact action."""
import hashlib
import json
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta

from copilot_mcp.guardrails.store import GuardrailStore

TTL = timedelta(minutes=5)
MAX_PENDING = 5  # A flood of requests is how people get worn down into approving without reading.


def args_hash(tool: str, args: dict) -> str:
    canonical = json.dumps({"tool": tool, "args": args}, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


class ApprovalError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Approval:
    id: str
    subject: str
    tool: str
    case_id: str
    etag: str | None
    preview: dict
    status: str
    expires_at: str


def _approval(row) -> Approval:
    return Approval(
        row["id"],
        row["subject"],
        row["tool"],
        row["case_id"],
        row["etag"],
        json.loads(row["preview"]),
        row["status"],
        row["expires_at"],
    )


class ApprovalStore:
    def __init__(self, store: GuardrailStore):
        self._store = store

    def request(self, subject, agent, tool, case_id, digest, etag, preview, now) -> Approval:
        approval_id = f"apr_{secrets.token_hex(16)}"
        expires = now + TTL
        with self._store.connect() as db:
            pending = db.execute(
                "SELECT COUNT(*) FROM approvals "
                "WHERE subject = ? AND agent = ? AND status = 'pending' AND expires_at > ?",
                (subject, agent, now.isoformat()),
            ).fetchone()[0]
            if pending >= MAX_PENDING:
                raise ApprovalError(
                    "too_many_pending_approvals",
                    f"{pending} approvals are already waiting for this user. Stop requesting "
                    "more, and tell the user what is waiting.",
                )
            db.execute(
                "INSERT INTO approvals (id, subject, agent, tool, case_id, args_hash, etag, "
                "preview, status, created_at, expires_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
                (
                    approval_id,
                    subject,
                    agent,
                    tool,
                    case_id,
                    digest,
                    etag,
                    json.dumps(preview),
                    now.isoformat(),
                    expires.isoformat(),
                ),
            )
        return Approval(
            approval_id, subject, tool, case_id, etag, preview, "pending", expires.isoformat()
        )

    def get(self, approval_id: str) -> Approval | None:
        with self._store.connect() as db:
            row = db.execute("SELECT * FROM approvals WHERE id = ?", (approval_id,)).fetchone()
        return _approval(row) if row else None

    def decide(self, approval_id: str, approver: str, approve: bool, now: datetime) -> Approval:
        """Called only from the approval CLI, with an identity the caller has already verified.
        No MCP tool reaches this, and that absence is tested."""
        status = "approved" if approve else "denied"
        with self._store.connect() as db:
            row = db.execute("SELECT * FROM approvals WHERE id = ?", (approval_id,)).fetchone()
            if row is None:
                raise ApprovalError("approval_not_found", "No such approval.")
            if row["subject"] != approver:
                raise ApprovalError(
                    "approval_wrong_user", "Only the user the agent is acting for can decide."
                )
            if row["status"] != "pending":
                raise ApprovalError("approval_not_pending", f"This is already {row['status']}.")
            if row["expires_at"] <= now.isoformat():
                raise ApprovalError("approval_expired", "This approval has expired.")
            db.execute(
                "UPDATE approvals SET status = ?, decided_by = ? WHERE id = ?",
                (status, approver, approval_id),
            )
        return self.get(approval_id)

    def consume(self, approval_id, subject, agent, tool, digest, now) -> Approval:
        """Checks that this user is doing exactly what was approved, then uses it up."""
        with self._store.connect() as db:
            row = db.execute("SELECT * FROM approvals WHERE id = ?", (approval_id,)).fetchone()
            # The same answer whether it's unknown or someone else's, which reveals nothing.
            if row is None or row["subject"] != subject or row["agent"] != agent:
                raise ApprovalError("approval_not_found", "No such approval for this user.")
            if row["tool"] != tool or row["args_hash"] != digest:
                raise ApprovalError(
                    "approval_mismatch",
                    "These arguments differ from what the person approved. Call again without "
                    "approval_id to request approval for this exact action.",
                )
            if row["status"] == "pending":
                if row["expires_at"] <= now.isoformat():
                    raise ApprovalError("approval_expired", "It expired. Request a new one.")
                raise ApprovalError(
                    "approval_pending",
                    "The person hasn't approved this yet. Tell them what is waiting, then stop "
                    "and wait for them. You can't approve it yourself.",
                )
            if row["status"] == "denied":
                raise ApprovalError("approval_denied", "The person denied this. Don't retry it.")
            if row["expires_at"] <= now.isoformat():
                raise ApprovalError("approval_expired", "It expired. Request a new one.")
            # The one check for reuse. Conditional, so two concurrent calls can't both use it.
            updated = db.execute(
                "UPDATE approvals SET status = 'used' WHERE id = ? AND status = 'approved'",
                (approval_id,),
            ).rowcount
            if not updated:
                raise ApprovalError("approval_used", "This approval was already used.")
        return _approval(row)

    def restore(self, approval_id: str) -> None:
        """For a retryable failure. The idempotency key derives from the approval's ID, so another
        attempt can't apply the write twice."""
        with self._store.connect() as db:
            db.execute(
                "UPDATE approvals SET status = 'approved' WHERE id = ? AND status = 'used'",
                (approval_id,),
            )
