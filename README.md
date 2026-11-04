# Customer Entitlement & Support Case API

One governed API and MCP server over a case system, an entitlement system and a usage warehouse, for traditional apps and AI agents. All upstreams are local mocks with made-up data.

The platform is Java 21 and Spring Boot 4, including the mocks. Only the MCP server is Python, because it was already proven against a real model; it talks to the platform over HTTP like any other client. `DECISIONS.md` covers that choice.

```
LLM client ─stdio→ MCP server ─exchanged token→ Platform API ─→ mock Cases (JSON REST)
                     │  guardrails                 │  ├─────→ mock Entitlements (CSV + XML)
                     ▼                             ▼  └─────→ mock Warehouse (SQL)
                  mcp.db                     platform.db ─outbox→ signed webhook → sample receiver
             (audit, approvals, limits)      (idempotency, audit, subscriptions)
Mock identity provider: signs JWTs, publishes JWKS, performs RFC 8693 token exchange
```

Diagrams of the data flow, components, request paths and data model are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Run it
**You need:** a JDK, version 21 or later (`brew install openjdk` is picked up automatically; otherwise set `JAVA_HOME`), [uv](https://docs.astral.sh/uv/) for the MCP server, Node (for `npx`), and `make`. Maven comes with the repo. For `make lint`, also `brew install oasdiff`. For the live LLM demo, also the signed-in `claude` CLI. There is no Docker and nothing to configure.

```sh
make run             # API on :8000, mocks on :9001, receiver on :9002, identity provider on :9100
```

In a second terminal:
```sh
TOKEN=$(make -s token PERSONA=finance)
curl -s localhost:8000/v1/customers/ACC-007 -H "Authorization: Bearer $TOKEN"

make -s token PERSONA=support   # The same call with this token withholds ARR.

# Make the warehouse fail, and see the response degrade instead of erroring:
curl -X POST localhost:9001/__chaos -H 'content-type: application/json' \
  -d '{"upstream":"usage","errorStatus":500}'
curl -s localhost:8000/v1/customers/ACC-007 -H "Authorization: Bearer $TOKEN"
curl -X DELETE localhost:9001/__chaos
```

Personas are `support`, `finance`, `customer-success` and `platform-admin`.

## Test it
```sh
make test      # JUnit and pytest, the next two lines. Needs ports 18000, 19001, 19002, 19100 free.
make test-java # JUnit: the API, the mocks and the contract checks, on random ports.
make test-mcp  # pytest: MCP guardrails and the Schemathesis fuzz run, against the built jar.
make lint      # Spec validation, Spectral, breaking-change detection, and Ruff on the Python.
make postman   # Functional and regression collection. Starts the stack if it isn't running.
```

## See the agent work
```sh
make demo-scripted   # With `make run` going. A scripted fooled agent, with no LLM or account needed.
make demo            # With `make run` going. A real model; rewrites mcp/demo/transcript.md.
```

To use the server from your own MCP client, use `mcp/demo/run_demo.py` as the reference for the config. The agent should get only these four tools, because with a shell it could run the approval command itself. If a tool asks for approval, the person runs:
```sh
make approve ID=apr_... PERSONA=support
```

## Where to find each requirement
Java paths are under `src/main/java/com/example/platform/` unless they say otherwise, and test classes are under `src/test/java/com/example/`.

| Requirement | Where | Proof |
|---|---|---|
| **A. Contract** | [openapi.yaml](openapi.yaml) | `make lint` |
| Error model, versioning, auth, idempotency, ETags, rate limits | `openapi.yaml`, [.spectral.yaml](.spectral.yaml) | `SpecTest`, `ToolingTest` |
| Why REST | [DECISIONS.md](DECISIONS.md), and the spec's description | |
| **B. Aggregated customer and partial failure** | `api/CustomerController` | `CustomerTest` |
| Cases, filters, cursor pagination | `api/CaseController`, `web/Cursors` | `CaseTest`, `CursorsTest` |
| Idempotent comments, escalation rules | `idempotency/Idempotency`, `api/CaseWriteController`, `domain/EscalationRules` | `CommentTest`, `EscalationTest`, `EscalationRulesTest` |
| Webhook sender and receiver, signature, outbox (stretch) | `webhook/OutboxWorker`, `webhook/Webhooks`, `mocks/receiver/ReceiverController` | `WebhookTest`, `WebhookPrimitivesTest` |
| Timeouts, retries, breaker | `upstream/UpstreamClient`, `upstream/RetryBudget`, `upstream/Sources` | `UpstreamClientTest`, `ReviewRegressionTest` |
| Schema, data-model rationale, indexes | `src/main/resources/platform-schema.sql`, `DECISIONS.md` | |
| Contract tests | `src/test/java/com/example/contract/`, `tests/contract/` | `make test` |
| Postman collection | [postman/](postman/) | `make postman`, `PostmanCollectionTest` |
| Structured logs, correlation IDs | `web/CorrelationFilter` | `CustomerTest`, `tests/contract/test_schemathesis.py` |
| **C. MCP server and tools** | `mcp/copilot_mcp/server.py`, `tools.py` | `tests/guardrails/test_tool_design.py` |
| Identity propagation ([the flow](DECISIONS.md#identity-flow)) | `mcp/copilot_mcp/guardrails/identity.py`, `mocks/idp/IdpController` | `IdpTest`, `tests/guardrails/test_attacks.py` |
| Human approval | `mcp/copilot_mcp/guardrails/approval.py`, `mcp/demo/approve.py` | `tests/guardrails/test_attacks.py` |
| Injection, redaction, size limits | `mcp/copilot_mcp/guardrails/sanitize.py` | `tests/guardrails/test_attacks.py`, `test_sanitize.py` |
| Agent limits, audit of every call | `guardrails/ratelimit.py`, and `AuditMiddleware` in `server.py` | `tests/guardrails/test_audit.py` |
| LLM demo and failure cases | [mcp/demo/transcript.md](mcp/demo/transcript.md) | `make demo` |
| **D. Platform** | [PLATFORM.md](PLATFORM.md) | |
| Trade-offs, skipped work | [DECISIONS.md](DECISIONS.md) | |
| AI usage | [AI_USAGE.md](AI_USAGE.md) | |

## Worth knowing
- **Data resets:** mock cases reset when the stack restarts or on `POST :9001/__reset`, with dates relative to today. SQLite files in `var/` persist, so delete `var/` for a completely clean start.
- **The sample question's answer** is CS-10231, CS-10240 and CS-10244, stated by hand in `mocks/upstreams/Seed.java`, so tests can check it.
- **Biggest known gap:** human approval is enforced in the MCP server, and the API can't yet verify it. See "Known limits" in `DECISIONS.md`.
