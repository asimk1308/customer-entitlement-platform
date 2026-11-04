"""Fuzzes the running Java stack against openapi.yaml. Schemathesis is the one Python tool here that
is about the API rather than MCP, and it does not care what the API is written in."""
import json
import os
import subprocess
from pathlib import Path

import httpx
import pytest

ROOT = Path(__file__).parents[2]
API, IDP = 18000, 19100
EXAMPLES = "40"
SEED = "1"


@pytest.fixture(scope="module")
def fuzz_run(stack):
    token = httpx.post(
        f"http://localhost:{IDP}/dev/token", json={"persona": "contract-test"}
    ).json()["access_token"]
    env = os.environ | {"SCHEMATHESIS_HOOKS": "st_hooks", "PYTHONPATH": str(ROOT / "tests/contract")}
    return subprocess.run(
        [
            str(ROOT / ".venv/bin/schemathesis"),
            "run",
            "openapi.yaml",
            "--url",
            f"http://localhost:{API}",
            "-H",
            f"Authorization: Bearer {token}",
            "--max-examples",
            EXAMPLES,
            "--seed",
            SEED,
            # Replaced by valid_requests_pass_validation in st_hooks.py.
            "--exclude-checks",
            "positive_data_acceptance",
        ],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )


def test_api_conforms_to_the_contract_under_fuzzing(fuzz_run):
    assert fuzz_run.returncode == 0, fuzz_run.stdout[-6000:]


def test_every_operation_was_exercised(fuzz_run):
    assert "Selected: 7/7" in fuzz_run.stdout
    assert "Tested: 7" in fuzz_run.stdout


def test_server_logs_stayed_structured_with_correlation_ids_and_no_tokens(fuzz_run, stack):
    lines = [line for line in stack["log"].read_text().splitlines() if line.startswith("{")]
    entries = [json.loads(line) for line in lines]
    completed = [entry for entry in entries if entry["message"] == "request_completed"]
    assert len(completed) > 100
    assert all(entry["correlationId"] for entry in completed)
    assert "Bearer" not in stack["log"].read_text()
    assert "eyJ" not in stack["log"].read_text()  # The start of any encoded JWT.
    assert not [entry for entry in entries if entry["message"] == "unhandled_error"]
