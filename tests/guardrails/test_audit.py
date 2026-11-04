import sqlite3

import pytest


def test_every_outcome_is_audited(support, escalation, chaos):
    support.ok("search_cases", limit=1)
    support.call("search_cases", limit=999)
    support.call("no_such_tool")
    support.ok("escalate_case", **escalation)
    chaos("cases", errorStatus=500)
    support.call("search_cases")

    rows = support.audit()
    assert [(row["tool"], row["outcome"], row["decision"]) for row in rows] == [
        ("search_cases", "success", "allowed"),
        ("search_cases", "error", "allowed"),
        ("no_such_tool", "error", "allowed"),
        ("escalate_case", "success", "approval_required"),
        ("search_cases", "error", "allowed"),
    ]
    assert {row["subject"] for row in rows} == {"jdoe"}
    assert {row["agent"] for row in rows} == {"support-copilot"}


def test_blocked_attempts_record_why(support, escalation):
    approval_id = support.ok("escalate_case", **escalation)["approval_id"]
    support.call("escalate_case", **escalation, approval_id=approval_id)
    assert support.audit()[-1]["decision"] == "blocked:approval_pending"


def test_rate_limited_calls_are_audited(support):
    for _ in range(61):
        support.call("search_cases", limit=1)
    assert support.audit()[-1]["decision"] == "rate_limited:read"


def test_tool_call_joins_to_the_api_audit_by_correlation_id(support, escalation, stack):
    approval_id = support.ok("escalate_case", **escalation)["approval_id"]
    support.human_approves(approval_id)
    support.ok("escalate_case", **escalation, approval_id=approval_id)
    tool_row = support.audit()[-1]
    api_row = sqlite3.connect(stack["db"]).execute(
        "SELECT actor, agent, outcome, correlation_id FROM audit_log "
        "WHERE action = 'case.escalate' AND correlation_id = ?",
        (tool_row["correlation_id"],),
    ).fetchone()
    assert api_row == ("jdoe", "support-copilot", "success", tool_row["correlation_id"])


def test_free_text_is_hashed_not_stored(support, escalation):
    secret = "Customer jane.roe@example.com is furious."
    support.ok("escalate_case", **(escalation | {"justification": secret}))
    support.ok("add_case_comment", case_id="CS-10232", body=secret)
    text = str(support.audit())
    assert secret not in text
    assert "jane.roe" not in text
    assert support.audit()[0]["args_hash"]


def test_identifiers_are_kept_for_investigation(support, escalation):
    support.ok("escalate_case", **escalation)
    assert '"case_id": "CS-10231"' in support.audit()[0]["args"]


def test_audit_cannot_be_changed_or_deleted(support):
    support.ok("search_cases", limit=1)
    db = sqlite3.connect(support.db_path)
    for statement in ("UPDATE tool_audit SET subject = 'x'", "DELETE FROM tool_audit"):
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            db.execute(statement)
