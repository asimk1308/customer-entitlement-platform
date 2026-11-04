# Platform best practices and NFRs

Targets and tool names are proposals. What exists in this repo is called out; the rest is how I would take it to production.

## 1. API gateway placement
The gateway handles what is true of every request; the service handles what needs to understand the data.

| Concern | Gateway | Service |
|---|---|---|
| Authentication | Validate signature, issuer, expiry, audience | Validate again, since a bypass must not mean open access |
| Authorization | Coarse route access | Scopes, ARR per field, accounts per resource |
| Rate limiting | Per client and IP | Per user and agent pair |
| Schema validation | Size and content-type limits | Full validation |
| Transformation | None: it would escape tests and review | Adapters per upstream |
| Other | TLS, WAF, routing | Idempotency, business rules, resilience |

**MCP traffic path:** agent client → gateway → MCP server → gateway → API → upstreams. The repo uses stdio, one user per process. Multi-user means Streamable HTTP with OAuth, limits and approvals behind a shared service, and tool allow-lists per agent at an MCP-aware gateway.

## 2. NFRs and SLAs
| | Availability | p95 | p99 | Throughput |
|---|---|---|---|---|
| `GET /v1/customers/{id}` | 99.9% | 300 ms | 800 ms | 200 rps |
| `GET /v1/cases` | 99.9% | 400 ms | 1 s | 100 rps |
| MCP read tools | 99.5% | 500 ms | 1.2 s | 50 calls/s |
| MCP write tools, excluding human wait | 99.5% | 1 s | 2 s | 5 calls/s |

**SLIs:** availability is the share of non-5xx requests, and latency is measured at the gateway. Degraded rate is separate, with a 1% target, as counting `degraded: true` as healthy would hide an outage. Agents add approval wait, denial and injection-flag rates.

**Alerting:** over 30 days, 99.9% allows 43 minutes of errors and 99.5% allows 3.6 hours. Page on burn rate over two windows: 14.4× over 1 hour with 5-minute confirmation, then 6× over 6 hours. Ticket at 1× over 3 days. A spent budget puts reliability ahead of features.

## 3. Observability
**Logs:** structured JSON with correlation IDs, in this repo. Never bodies, tokens or ARR, and a test checks that. **Metrics:** rate, errors and latency per route and consumer; upstream retries and breaker state; outbox depth; tool calls by decision. **Traces:** OpenTelemetry through gateway, MCP server, API and upstreams. The correlation ID already joins a tool call to its API audit row.

**Dashboard, top to bottom:** SLOs and budget left; upstream breaker state, latency and degraded rate; affected consumers; agent decisions, approvals waiting and injection flags; outbox lag and dead letters.

## 4. Governance
**Standards:** contract first; RFC 9457 errors, cursor pagination, idempotency keys and correlation IDs; deprecation headers with 6 months' notice; tool descriptions reviewed like code, since a wrong one becomes a false statement to a user. **Automated:** `.spectral.yaml` encodes the rules and `oasdiff` blocks breaking changes, both in `make lint`. Tests prove each can fail. **Review checklist:** consumers named, aggregation justified, error cases covered, field-level access stated, SLO proposed, deprecation plan. Docs render from the spec, with an example per operation.

**Adoption without formal authority:** make the right way the easy way, with a template repo, shared ruleset and generated SDKs. Start with one or two willing teams as the reference. Roll out rules in warn mode with a scorecard, and block once most teams pass. Review within a day, so the platform team is help rather than a queue. Grant exceptions with an expiry date, or rules get worked around in secret. Tie it to what leaders already care about, such as audit findings or incident counts.

## 5. Security and compliance
**Secrets:** a secret manager or workload identity, with scheduled rotation and webhook secrets encrypted by a KMS key. **mTLS:** a service mesh with SPIFFE identities. mTLS proves which workload is calling, and tokens prove on whose behalf. **Network:** no public route except through the gateway. Use private connectivity where offered (PrivateLink to Snowflake and Salesforce) and allow-listed egress elsewhere, including for webhooks.

**Audit logging:** the repo has append-only tables enforced by triggers, holding outcomes and identifiers, not free text. Production ships them to immutable storage such as S3 Object Lock. **Change control:** authors can't approve or deploy their own change, and signed commits and an evidence trail link change, review, tests and deployment. For SOX, ARR is financial data, so Finance scope access reviews are logged. For FedRAMP, deploy inside the boundary with FIPS-validated crypto, and confirm each upstream and LLM provider is authorized. **Agents:** least privilege per call, and approval outside the model's reach. First gap to close: the API can't yet verify an approval (`DECISIONS.md`).

## 6. Reuse
Publish a **catalog entry** with owner, SLOs, scopes, samples and an access request route. Generate **SDKs** from the spec, with retries and idempotency built in. Import the spec into MuleSoft or Workato as an **iPaaS connector**. Turn the **MCP guardrails** into a library. Offer a **golden path**: template, Spectral ruleset and CI pipeline. Measure consumers per API, time to first call, and duplicates retired.

## 7. CI/CD
| Stage | Runs | Blocks on | In this repo |
|---|---|---|---|
| Lint | Ruff, Spectral, spec validation, `oasdiff` | Any violation or breaking change | `make lint` |
| Tests and contract | JUnit, pytest, Schemathesis, Postman | Any failure | `make test`, `make postman` |
| Security and build | SAST, dependency, secret and image scans; signed image, SBOM | High severity | Proposed |
| Staging | Smoke tests, scripted MCP demo | Any failure | `make demo-scripted` |
| Canary, rollback | 5%, 25%, 100%; automatic rollback; backward-compatible database changes | SLO burn rate | Proposed |

A scheduled job adds random-seed fuzzing and live agent evals.

## 8. iPaaS decision
| Part of this scenario | Choice | Why |
|---|---|---|
| NetSuite CSV and XML ingestion | iPaaS | Scheduling, file handling and retries are standard |
| SaaS flows and workflows using this API, such as a Slack alert on `case.escalated` | iPaaS | Business-owned, change often, low risk |
| API, aggregation, partial-failure rules | Custom | Latency and field-level access are hard to verify in low-code |
| Idempotency, signing, MCP guardrails | Custom | Security-critical, and need real tests |

iPaaS moves data; custom code holds rules that must be correct, tested and owned. Workato suits business-owned automation, and MuleSoft heavier, IT-owned integration.
