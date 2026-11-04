"""Tool schemas and outputs: what a model is told, and how much of its context each call costs."""
import json

import pytest


def tool_map(session):
    return {tool.name: tool for tool in session.list_tools()}


def test_all_schemas_reject_unknown_arguments_and_all_have_output_schemas(support):
    for tool in support.list_tools():
        assert tool.input_schema["additionalProperties"] is False, tool.name
        assert tool.output_schema, tool.name


def test_no_token_wasting_noise_in_input_schemas(support):
    for tool in support.list_tools():
        text = json.dumps(tool.input_schema)
        assert '"title"' not in text, tool.name
        assert "anyOf" not in text, tool.name


def test_closed_choices_are_enums(support):
    tools = tool_map(support)
    search = tools["search_cases"].input_schema["properties"]
    assert search["priority"]["items"]["enum"] == ["P1", "P2", "P3", "P4"]
    assert search["status_group"]["enum"] == ["active", "closed", "any"]
    assert search["limit"]["maximum"] == 25
    escalate = tools["escalate_case"].input_schema["properties"]
    assert "sla_breach" in escalate["reason_code"]["enum"]
    assert escalate["justification"]["maxLength"] == 500


def test_required_fields_are_only_what_is_needed(support):
    tools = tool_map(support)
    assert "required" not in tools["search_cases"].input_schema
    assert tools["escalate_case"].input_schema["required"] == [
        "case_id",
        "reason_code",
        "justification",
    ]


def test_risk_is_stated_to_the_model_and_to_clients(support):
    tools = tool_map(support)
    assert tools["escalate_case"].description.startswith("HIGH RISK")
    assert tools["escalate_case"].annotations.destructive_hint is True
    assert tools["search_cases"].annotations.read_only_hint is True


def test_tool_order_is_stable(support):
    assert [t.name for t in support.list_tools()] == [t.name for t in support.list_tools()]


@pytest.mark.parametrize(
    "tool, args",
    [
        ("search_cases", {"limit": 26}),
        ("search_cases", {"priority": ["P9"]}),
        ("search_cases", {"status_group": "open"}),
        ("get_customer_summary", {"account_id": "'; DROP TABLE usage;--"}),
        ("escalate_case", {"case_id": "CS-1", "reason_code": "because", "justification": "x" * 20}),
        ("escalate_case", {"case_id": "CS-1", "reason_code": "sla_breach", "justification": "x"}),
        ("add_case_comment", {"case_id": "../admin", "body": "x"}),
        ("add_case_comment", {"case_id": "CS-1", "body": "x", "approval_id": "made-up"}),
    ],
)
def test_bad_arguments_are_tool_errors_a_model_can_fix(support, tool, args):
    result = support.call(tool, **args)
    assert result.is_error


def test_sample_question_is_answered_in_one_call(support):
    result = support.ok(
        "search_cases",
        priority=["P1"],
        support_tier=["Platinum"],
        min_age_hours=48,
        renewal_within_days=90,
    )
    assert {row["case_id"] for row in result["cases"]} == {"CS-10231", "CS-10240", "CS-10244"}
    assert result["has_more"] is False
    row = next(row for row in result["cases"] if row["case_id"] == "CS-10231")
    # The question asks which customers. Found in a live run, when the model could only answer in IDs.
    assert row["customer"] == "Acme Corp"
    assert row["age_hours"] == 216
    assert row["days_to_renewal"] == 74


def test_one_case_can_be_looked_up_by_id_wherever_it_would_sort(support):
    """Found in a live run. CS-11029 is too low-priority to appear on a first page of results."""
    first_page = support.ok("search_cases", limit=25)["cases"]
    assert "CS-11029" not in {row["case_id"] for row in first_page}
    result = support.ok("search_cases", case_id="CS-11029")
    assert [row["case_id"] for row in result["cases"]] == ["CS-11029"]
    assert result["has_more"] is False


def test_looking_up_an_unknown_case_is_a_usable_error(support):
    assert support.error("search_cases", case_id="CS-999").startswith("not_found")


def test_looking_up_a_case_respects_the_users_accounts(copilot):
    assert copilot("customer-success").error(
        "search_cases", case_id="CS-10231"
    ).startswith("not_found")


def test_escalation_description_claims_only_what_the_tool_does(support):
    """Found in a live run: the model told a user engineers had been paged, repeating this text."""
    description = tool_map(support)["escalate_case"].description
    assert "case.escalated" in description
    for overclaim in ("on-call", "alerts", "pages"):
        assert overclaim not in description


def test_active_includes_escalated_and_excludes_closed(support):
    rows = support.ok("search_cases", account_id="ACC-012", priority=["P1"])["cases"]
    assert "CS-10241" not in {row["case_id"] for row in rows}  # Closed.
    escalated = support.ok("search_cases", account_id="ACC-019", priority=["P1"])["cases"]
    assert {row["status"] for row in escalated} == {"escalated"}


def test_paging_works_through_the_tool(support):
    first = support.ok("search_cases", status_group="any", limit=10, sort="newest_first")
    second = support.ok(
        "search_cases",
        status_group="any",
        limit=10,
        sort="newest_first",
        cursor=first["next_cursor"],
    )
    ids = {row["case_id"] for row in first["cases"]}
    assert not ids & {row["case_id"] for row in second["cases"]}


def text_the_model_reads(result):
    assert not result.is_error
    return result.content[0].text


def test_page_of_results_costs_much_less_than_the_raw_api(support, api, auth):
    active = {"limit": 25, "status": ["open", "in_progress", "escalated"], "sort": "priority"}
    raw = api.get("/v1/cases", params=active, headers=auth()).text
    compact = text_the_model_reads(support.call("search_cases", limit=25))
    assert len(compact) < 0.75 * len(raw), (len(compact), len(raw))


def test_text_and_structured_forms_carry_the_same_data(support):
    result = support.call("search_cases", limit=5)
    assert json.loads(result.content[0].text) == result.structured_content


def test_text_is_compact_with_no_indentation(support):
    assert "\n" not in text_the_model_reads(support.call("search_cases", limit=5))


def test_empty_values_are_left_out_but_has_more_stays(support):
    result = support.ok("search_cases", account_id="ACC-033")
    assert "has_more" in result
    text = json.dumps(result)
    assert "null" not in text
    assert "injection_suspected" not in text


def test_raw_upstream_fields_never_appear(support):
    text = json.dumps(support.ok("search_cases", account_id="ACC-007"))
    for leaked in ('"Status"', '"Comments"', '"etag"', '"openedAt"', '"version"'):
        assert leaked not in text


def test_pii_and_amounts_in_free_text_are_redacted(support, upstream):
    upstream.change(
        "CS-10233",
        latestCommentBody=(
            "Call jane.roe@example.com or +1 415 555 0132. They pay $480,000 a year. "
            "Renews 2027-01-15."
        ),
    )
    rows = support.ok("search_cases", account_id="ACC-007", priority=["P2"])["cases"]
    comment = rows[0]["untrusted_latest_comment"]
    assert "jane.roe" not in comment
    assert "555" not in comment
    assert "480,000" not in comment
    assert "2027-01-15" in comment  # Dates aren't phone numbers.


def test_long_text_is_cut_to_a_bound(support, upstream):
    upstream.change("CS-10233", subject="s" * 5000, latestCommentBody="c" * 5000)
    row = support.ok("search_cases", account_id="ACC-007", priority=["P2"])["cases"][0]
    assert len(row["untrusted_subject"]) == 200
    assert len(row["untrusted_latest_comment"]) == 280


def test_oversized_results_say_so_instead_of_silently_losing_rows(support, upstream):
    for case in upstream.cases():
        fields = {"subject": "s" * 5000}
        if case["Comments"]:
            fields["latestCommentBody"] = "c" * 5000
        upstream.change(case["id"], **fields)
    result = support.ok("search_cases", status_group="any", limit=25)
    assert len(json.dumps(result)) < 8500
    assert result["has_more"] is True
    assert "next_cursor" not in result  # The API's cursor would skip the dropped rows.
    assert "Narrow the filters" in result["notice"]


def test_degraded_sources_reach_the_model(support, chaos):
    chaos("usage", errorStatus=500)
    summary = support.ok("get_customer_summary", account_id="ACC-007")
    assert summary["degraded_sources"] == ["usage"]
    assert "protected_tb" not in summary
    assert summary["support_tier"] == "Platinum"


def test_upstream_failure_gives_a_usable_error_without_internals(support, chaos):
    chaos("cases", errorStatus=500)
    message = support.error("search_cases")
    assert message.startswith("upstream_error")
    assert "injected" not in message
    assert "Traceback" not in message
