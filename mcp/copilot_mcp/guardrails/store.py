"""The MCP server's own SQLite file, separate from the API's. It survives restarts, which matters
because a stdio server is a new process per session: in-memory limits would reset each time."""
import sqlite3
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS approvals (
    id          TEXT PRIMARY KEY,
    subject     TEXT NOT NULL,
    agent       TEXT NOT NULL,
    tool        TEXT NOT NULL,
    case_id     TEXT NOT NULL,
    args_hash   TEXT NOT NULL,
    etag        TEXT,
    preview     TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'denied', 'used')),
    created_at  TEXT NOT NULL,
    expires_at  TEXT NOT NULL,
    decided_by  TEXT
);
CREATE INDEX IF NOT EXISTS idx_approvals_pending ON approvals (subject, agent, expires_at)
    WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS rate_limits (
    key          TEXT PRIMARY KEY,
    window_start INTEGER NOT NULL,
    count        INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tool_audit (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    at             TEXT NOT NULL,
    subject        TEXT NOT NULL,
    agent          TEXT NOT NULL,
    tool           TEXT NOT NULL,
    args_hash      TEXT NOT NULL,
    args           TEXT NOT NULL,  -- identifiers and enums only, never free text
    decision       TEXT NOT NULL,
    outcome        TEXT NOT NULL,
    correlation_id TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tool_audit_subject ON tool_audit (subject, at);
CREATE INDEX IF NOT EXISTS idx_tool_audit_correlation ON tool_audit (correlation_id);
CREATE TRIGGER IF NOT EXISTS tool_audit_no_update BEFORE UPDATE ON tool_audit
BEGIN SELECT RAISE(ABORT, 'tool_audit is append-only'); END;
CREATE TRIGGER IF NOT EXISTS tool_audit_no_delete BEFORE DELETE ON tool_audit
BEGIN SELECT RAISE(ABORT, 'tool_audit is append-only'); END;
"""


class GuardrailStore:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()
