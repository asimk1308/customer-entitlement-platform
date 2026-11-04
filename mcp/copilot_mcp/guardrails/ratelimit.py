"""Fixed-window limits per agent and user, stored so that they persist between stdio sessions."""
from datetime import datetime

from copilot_mcp.guardrails.store import GuardrailStore

# bucket -> (allowed, window in seconds)
LIMITS = {
    "read": (60, 60),
    "approval_request": (10, 3600),
    "comment": (10, 3600),
    # The hard stop on "escalate all cases": however many approvals exist or are granted.
    "escalate": (3, 3600),
}


class RateLimiter:
    def __init__(self, store: GuardrailStore):
        self._store = store

    def try_acquire(self, agent: str, subject: str, bucket: str, now: datetime) -> int | None:
        """Returns None if allowed, otherwise the seconds until the window resets."""
        allowed, window_seconds = LIMITS[bucket]
        moment = int(now.timestamp())
        window = moment // window_seconds * window_seconds
        key = f"{agent}|{subject}|{bucket}"
        with self._store.connect() as db:
            # One statement, so that concurrent calls can't both read a count and both pass.
            row = db.execute(
                "INSERT INTO rate_limits (key, window_start, count) VALUES (?, ?, 1) "
                "ON CONFLICT (key) DO UPDATE SET "
                "count = CASE WHEN window_start = excluded.window_start THEN count + 1 ELSE 1 END, "
                "window_start = excluded.window_start "
                "RETURNING count",
                (key, window),
            ).fetchone()
        if row["count"] > allowed:
            return window + window_seconds - moment
        return None
