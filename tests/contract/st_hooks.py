"""A narrower form of Schemathesis's "valid data must be accepted" check.

A schema can't express that a cursor must be one we issued, or that an ETag must be current, so some
schema-valid requests are correctly refused. Turning the check off would also hide real bugs, such as
code stricter than the contract. So a valid request may be refused only for a named business reason,
and never with `validation_error`, which would mean the code and the contract disagree."""
import json

import schemathesis

VALIDATION_ERROR = "validation_error"


@schemathesis.check
def valid_requests_pass_validation(ctx, response, case):
    meta = case.meta
    if meta is None or not meta.generation.mode.is_positive:
        return
    if response.status_code != 400:
        return
    try:
        code = json.loads(response.content).get("code")
    except ValueError:
        code = None
    if code == VALIDATION_ERROR:
        raise AssertionError(f"Schema-valid request failed validation: {response.text[:300]}")
    return
