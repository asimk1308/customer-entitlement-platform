-- Only what this service owns. Case, entitlement and usage data stay in their source systems.

-- Explicit ID mapping, because source IDs can't safely be guessed. The primary key serves the only
-- lookup, which is by source and source ID.
CREATE TABLE IF NOT EXISTS account_crosswalk (
    source     TEXT NOT NULL,
    source_id  TEXT NOT NULL,
    account_id TEXT NOT NULL,
    note       TEXT,
    PRIMARY KEY (source, source_id)
);

-- Last-known-good entitlements, used when the source is down. It survives restarts, unlike the
-- in-memory cache. The whole set is replaced on each refresh, so no extra indexes are needed.
CREATE TABLE IF NOT EXISTS entitlement_snapshot (
    account_id TEXT PRIMARY KEY,
    payload    TEXT NOT NULL,
    as_of      TEXT NOT NULL
);

-- One row per logical write. The unique key makes claiming atomic, including across processes.
-- `upstream_attempted` records that the upstream may have applied the write, so a retry must not
-- trust pre-checks, which would see the write's own effect and wrongly reject it.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    principal          TEXT NOT NULL,  -- user and agent, so that two callers can't collide
    route              TEXT NOT NULL,
    key                TEXT NOT NULL,
    request_hash       TEXT NOT NULL,
    state              TEXT NOT NULL CHECK (state IN ('in_progress', 'completed')),
    upstream_attempted INTEGER NOT NULL DEFAULT 0,
    expected_version   INTEGER,
    locked_until       TEXT NOT NULL,  -- lets a retry take over from a crashed request
    response_status    INTEGER,
    response_body      TEXT,
    response_headers   TEXT,
    expires_at         TEXT NOT NULL,
    PRIMARY KEY (principal, route, key)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_expiry ON idempotency_keys (expires_at);

-- Append-only, enforced by the database rather than by convention. Holds outcomes and identifiers, not
-- free text, so that the audit trail doesn't become a second store of customer data.
CREATE TABLE IF NOT EXISTS audit_log (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    at             TEXT NOT NULL,
    actor          TEXT NOT NULL,
    agent          TEXT,
    action         TEXT NOT NULL,
    case_id        TEXT,
    account_id     TEXT,
    outcome        TEXT NOT NULL,
    correlation_id TEXT,
    detail         TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log (actor, at);  -- "what did this user do?"
CREATE INDEX IF NOT EXISTS idx_audit_case ON audit_log (case_id, at);  -- "who touched this case?"
CREATE TRIGGER IF NOT EXISTS audit_no_update BEFORE UPDATE ON audit_log
BEGIN SELECT RAISE(ABORT, 'audit_log is append-only'); END;
CREATE TRIGGER IF NOT EXISTS audit_no_delete BEFORE DELETE ON audit_log
BEGIN SELECT RAISE(ABORT, 'audit_log is append-only'); END;

CREATE TABLE IF NOT EXISTS webhook_subscriptions (
    id         TEXT PRIMARY KEY,
    owner      TEXT NOT NULL,
    url        TEXT NOT NULL,
    events     TEXT NOT NULL,
    secret     TEXT NOT NULL,
    active     INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_active ON webhook_subscriptions (active);

-- One row per event per subscriber, so one failing receiver can't hold up the others. Written in the
-- same transaction as the write's result, so a committed write can't lose its event.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        TEXT NOT NULL,
    subscription_id TEXT NOT NULL REFERENCES webhook_subscriptions (id),
    payload         TEXT NOT NULL,
    correlation_id  TEXT,
    status          TEXT NOT NULL CHECK (status IN ('pending', 'delivered', 'dead')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT NOT NULL,
    created_at      TEXT NOT NULL
);
-- Partial, so the poll stays fast however many delivered rows build up.
CREATE INDEX IF NOT EXISTS idx_outbox_due ON outbox_events (next_attempt_at)
    WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    outbox_id   INTEGER NOT NULL REFERENCES outbox_events (id),
    attempt     INTEGER NOT NULL,
    status_code INTEGER,
    error       TEXT,
    at          TEXT NOT NULL,
    PRIMARY KEY (outbox_id, attempt)
);
