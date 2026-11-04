# Start here

A governed Customer Entitlement & Support Case API and MCP server, built for the Senior AI API Platform Engineer take-home. All upstreams are local mocks with made-up data, and there is nothing to configure or deploy.

## Run it
**You need:** JDK 21 or later, [uv](https://docs.astral.sh/uv/), Node (for `npx`) and `make`. A first build takes about a minute.

```sh
make run
```

This starts the API on :8000, with mocks on :9001, :9002 and :9100. In a second terminal:

```sh
# Finance sees ARR.
curl -s localhost:8000/v1/customers/ACC-007 -H "Authorization: Bearer $(make -s token PERSONA=finance)"

# Support gets the same customer with ARR withheld.
curl -s localhost:8000/v1/customers/ACC-007 -H "Authorization: Bearer $(make -s token PERSONA=support)"

# A fooled agent tries to escalate without approval, with no LLM or account needed.
make -s demo-scripted
```

## Test it
Stop `make run` first if ports are in use.

```sh
make test      # JUnit and pytest
make postman   # Functional and regression collection
make lint      # Spec validation, Spectral, Ruff and oasdiff (needs: brew install oasdiff)
```

## Where to look
| Part of the brief | File |
|---|---|
| Architecture, data flow and low-level design diagrams | `docs/ARCHITECTURE.md` |
| A. API contract | `openapi.yaml` |
| B. Implementation and tests | `src/`, `tests/`, `postman/` |
| C. MCP server, and the live LLM transcript and failure cases | `mcp/`, `mcp/demo/transcript.md`, `mcp/demo/failure_cases.md` |
| D. Platform and NFRs | `PLATFORM.md` |
| Trade-offs, identity flow, skipped work and known limits | `DECISIONS.md` |
| How AI was used and how its output was checked | `AI_USAGE.md` |

`README.md` has the full run guide and maps each requirement to its code and tests.

**Biggest known gap:** human approval is enforced in the MCP server, and the API can't yet verify it. See "Known limits" in `DECISIONS.md`.
