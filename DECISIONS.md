# Decisions

What I chose, why, what it costs, and what I skipped. Platform-wide topics are in `PLATFORM.md`.

## Why Java, with Python only for MCP
The platform, including its mocks, is Java 21 and Spring Boot 4. It is the stack I have used for ten years, so it is what I can defend line by line, and Spring Security's resource server and Resilience4j replace code I would otherwise own.

The MCP server stays in Python. It was already proven against a real model, and it is a separate process with its own identity that talks to the API over HTTP, so the language boundary sits on a boundary the design already has. The cost is two toolchains in one repo, which `make` hides. An official Java MCP SDK exists, and I didn't evaluate it for this exercise: on a JVM-only team, that is the first thing I'd try.

This was first built in Python and then ported. Postman, Schemathesis and the MCP attack tests were kept as black-box acceptance checks, which is what shows the behaviour survived. `AI_USAGE.md` covers how that happened and what the port found.

## Why REST, and where GraphQL or gRPC would be better
REST fits this surface: it is small, and its consumers are varied. iPaaS connectors, scripts and agents all speak HTTP and OpenAPI, and gateways, ETags and per-route policies work with no extra machinery.

- **GraphQL** suits a large connected graph that clients explore with different field needs, such as a broad product API. Here it would complicate per-route limits and caching for little gain.
- **gRPC** suits high-volume internal calls where streaming and compact payloads matter and all clients can be generated, such as a future read-model service. iPaaS tools and agents wouldn't benefit.

The contract aggregates only where a consumer would otherwise need to know about several systems: `Customer` is a platform-owned aggregate, while `Case` stays close to its one source system.

## Stretch choice
I picked the event-driven path: a local outbox for `case.escalated`. A Kubernetes snippet would have been faster, but delivery reliability shows more engineering judgment. The queue is a SQLite table. Kafka or EventBridge would replace the polling worker, while the transactional write stays the same.

## Assumptions
The brief leaves these open. They are my choices, not requirements, and each is cheap to change.

| Assumption | Why | Where |
|---|---|---|
| Tiers are Platinum, Gold, Silver | The brief only shows Platinum | `SupportTier` |
| Statuses are open, in_progress, escalated, resolved, closed | The brief only shows `open`; escalation needs a state to move to | `CaseStatus` |
| P1/P2 can always be escalated; P3/P4 only for Platinum or Gold | The brief asks for "business rules" without naming any. This one needs data from a second system, so it tests the integration | `escalateCase` |
| Escalation reason codes | Enums suit agents and reporting better than free text | `EscalationReason` |
| Idempotency keys are kept for 24 hours | Covers client retries and overnight batch re-runs | `addCaseComment` |
| 6 months' deprecation notice | Internal consumers still need planning time | `info.description` |
| 8 webhook attempts, then dead-letter | With 1-minute to 1-hour backoff, spans about 2 hours of receiver downtime | `case.escalated` |
| `ACC-77` in the CSV is `ACC-007` | Same tier and renewal date in the appendix. Recorded as one crosswalk row with a note to confirm, because IDs must not be guessed | `account-crosswalk.csv` |
| The XML feed beats the CSV on tier and renewal | A feed is usually fresher than a batch export. Conflicts are reported in `dataQuality`, so a wrong guess stays visible | `Adapters.mergeEntitlements` |
| Case versions exist upstream | The appendix has none, but `If-Match` needs one. Salesforce has `LastModifiedDate` | `version` in the mock |
| Upstream writes accept an external ID | Needed to retry a write safely. Salesforce supports this through external ID fields | mock `externalId` |

## Resilience
| Decision | Why | Trade-off |
|---|---|---|
| Entitlement is a hard dependency of the customer view; usage is optional | Tier and renewal are what the view is for. Usage is context | A customer view can be served with `usage: null` |
| Snapshot fallback in SQLite, with `asOf` in the response | Entitlement changes slowly, so old data with its age shown beats an error, and the snapshot survives restarts | Consumers may act on stale tier data, so responses are flagged and never cached |
| Refuse tier and renewal filters when there is no entitlement data | An empty result would look like a real answer: "no Platinum customers at risk" | Lower availability for those queries, which is the honest choice |
| P3/P4 escalation fails closed if the tier is unknown; P1/P2 don't need it | A P1 must not be blocked by an unrelated outage, and P3/P4 eligibility can't be judged without the tier | None worth noting |
| Budgets: usage 0.5 s, entitlements 1.2 s, cases 2 s | Usage is optional, so it gets the least time, and stays inside the 800 ms p99 target | While a hung warehouse trips the breaker (5 failures), those requests take about 0.5 s |
| Breaker per upstream, plus a shared retry budget for each | Retries alone multiply load on a struggling upstream | Resilience4j's breaker, as it is the standard and I'd rather not own a state machine. The retry loop and budget are hand-written, because they must respect the per-call deadline and retry-safety rules |
| Consecutive failures open the breaker | A count window the size of the threshold at a 100% failure rate means N in a row, which is easy to reason about in an incident | Slower to open under a mix of failures and successes than a percentage over a larger window |
| Each attempt bounded by waiting on the whole `sendAsync` exchange | The JDK client's own timeout stops at the response headers, so a slow-dripping body could outlast it | None worth noting |
| One entitlement refresh at a time, and everyone waiting shares its outcome, including a failure | Taking turns to retry queued requests for 17 seconds behind one hung feed, which a review reproduced | Under a long outage, one refresh still runs at a time, so a request can wait up to the 1.2 s budget until the breaker opens |
| Separate breakers for the CSV and XML sources | With one breaker, a healthy CSV kept resetting the failure count of a hung feed | Two breakers log under the same `entitlements` name |
| An empty entitlement source counts as a failure | A broken export can answer 200, and accepting it would wipe the snapshot and turn customers into 404s | A real empty source would be reported as degraded |
| Unknown JWT key IDs fetch the key set at most twice per 30 seconds | Otherwise any anonymous caller can make the API call the identity provider on every request | A token from a newly rotated key can get a 401 for up to 30 seconds |
| Virtual threads, with `ReentrantLock` rather than `synchronized` | Blocking calls to upstreams stay simple to read and don't tie up platform threads. The target is Java 21, where `synchronized` pins a carrier thread | Needs Java 21 or later |

## Data model
SQLite in WAL mode through `JdbcClient` and a small Hikari pool: runs with no setup and is transactional. A move to Postgres would mainly mean changing `INSERT OR IGNORE` and the trigger syntax. `transaction_mode=IMMEDIATE` takes the write lock up front, so a read-then-write transaction can't fail halfway on a lock upgrade. Spring Data would add nothing to a few hand-written statements. Only data this service owns is stored; source data stays in its systems.

| Table | Purpose | Key or index, and why |
|---|---|---|
| `idempotency_keys` | Claiming and replaying writes | PK `(principal, route, key)` makes claiming atomic; `expires_at` index for purging |
| `audit_log` | Append-only record of mutations, enforced by triggers | `(actor, at)` for "what did this user do?", `(case_id, at)` for "who touched this case?" |
| `outbox_events` | Reliable webhook delivery, one row per subscriber | Partial index on `next_attempt_at WHERE status = 'pending'` keeps polling fast as delivered rows build up |
| `webhook_deliveries` | Attempt history for support | PK `(outbox_id, attempt)` |
| `webhook_subscriptions` | Targets and signing secrets | `active` index for fan-out |
| `account_crosswalk` | Explicit source-to-canonical ID mapping | PK `(source, source_id)` serves the only lookup |
| `entitlement_snapshot` | Last-known-good entitlements | PK `account_id`; the whole set is replaced on refresh, so no other index is needed |

The MCP server has its own file (`approvals`, `rate_limits`, `tool_audit`), so it needs no access to the API's database.

## Not built, and what I'd do instead
| Skipped | Why | With more time |
|---|---|---|
| Filter pushdown for `GET /v1/cases` | The list fetches all cases and filters in memory, which only works at mock scale | Keep a read model fed by change data capture, and index the filter columns. The contract wouldn't change |
| Distributed rate limiting | In-memory limits are per process | Coarse limits at the gateway, and a shared store such as Redis for per-agent limits |

## Writes
| Decision | Why | Trade-off |
|---|---|---|
| Record "upstream attempted" before calling upstream, and skip pre-checks on resume | After a lost response the case really has changed, so `If-Match` would wrongly report 412 for a write that worked | A resumed write relies on the upstream's version check and external ID |
| Forget the key when a write is rejected, keep it when the outcome is unknown | A rejection may become valid later, while an unknown outcome must not run as new | Rejections aren't replayed verbatim, unlike some APIs |
| External ID derived from principal, route and key | Stable across retries, so the upstream deduplicates even after our 24-hour record expires | A client wrongly reusing a key after expiry gets the original result, not a new write |
| Lease of 30 seconds on a claim | A crashed request must not block its retries forever | A retry during those 30 seconds gets a 409 and has to try again |
| If a stalled request and the retry that took over both finish, the first commits and the other returns its stored response | Both carry the same deduplicated write, so it must produce one audit row and one event | A request that lost its lease can't release the key, so a retry after its rejection waits for the lease |
| Event IDs derive from the external ID | Every retry of a write names the same event, so receivers can deduplicate | None worth noting |
| Response, audit row and outbox rows in one transaction | A committed write can't lose its event or its audit entry | They must share a database |
| One outbox row per event per subscriber | One failing receiver can't delay the others | More rows than a single event table |
| Audit rejections as well as successes, with outcomes and no free text | Denied attempts matter for security review, and the audit trail shouldn't hold customer data | Justification text lives only in the case system. A 401 or a missing-scope 403 stops before this layer, so those appear in the request logs, not `audit_log` |
| `If-Match` takes one exact ETag, with no `*` or lists | Escalating needs a decision based on one known version, and `*` would skip that check | Stricter than HTTP allows |
| Agents can only write internal comments | An injected instruction can't make an agent message a customer | An agent can't draft public replies directly |

## Not built for webhooks
| Skipped | With more time |
|---|---|
| Several workers at once | Claim rows with `SELECT ... FOR UPDATE SKIP LOCKED` on Postgres. There is one worker today, so no claim step is needed |
| Secret encryption at rest | HMAC needs the raw secret, so store it encrypted with a key from a KMS |
| Secret rotation endpoint | Receivers and the signature format already accept several secrets; only the endpoint and a second stored secret are missing |
| Dead-letter replay endpoint | Dead rows and delivery history are kept, so replay means resetting status through an admin-only route |
| Event history for new subscribers | Keep an event log, so that a new subscriber can read past events |

## Testing
| Decision | Why | Trade-off |
|---|---|---|
| A fresh API context per test, against shared mocks on real ports | Caches, breakers and limiters start clean, and every call crosses real HTTP. A context starts in about 100 ms, so this is affordable | Slower than mocking the web layer |
| Real faults rather than mocks: a SQLite trigger for a full disk, a dropped table for an unhandled error | They exercise the real exception path rather than a simulated one | Each scenario needs a way to cause the failure for real |
| Python tests run the MCP server against the jar | The guardrails depend on the API's real answers, so they should get them | The stack outlives each test, so the jar runs with breakers effectively off and tests reset the mock |
| Schemathesis runs against real processes | Tests the real server, sockets and middleware, not an in-process shortcut | About 10 seconds in `make test`, and it needs free ports 18000, 19001, 19002 and 19100 |
| A custom check replaces `positive_data_acceptance` | A schema can't express "a cursor we issued" or "the current ETag", so valid-looking requests are sometimes correctly refused. A narrower rule still fails on `validation_error` | One small hook file to maintain |
| The fixed seed and modest volume stay in `make test` | Results must be repeatable for reviewers | Fixed cases only. CI should add a scheduled job with random seeds and more examples |
| Rate limit raised during fuzzing | Otherwise the run mostly tests the limiter, as my first heavy run did | The 429 path is covered by integration tests instead |

**Fuzzing limit:** generated requests can't produce a current `ETag`, so fuzzing rarely reaches a successful escalation. That path is covered by integration tests that validate responses against the same schemas, and by the Postman collection.

## MCP tools: why they differ from REST
REST serves many consumers and stays general. Tools serve one consumer, a model with limited context, so they are built around its tasks.

| Tool | Differs from REST by | Why |
|---|---|---|
| `search_cases` | `status_group` instead of `status`, computed `age_hours` and `days_to_renewal`, max 25 rows, snippets | `active` includes `escalated`, so the model can't miss urgent cases. Date arithmetic is a common model error, so it is done here |
| `get_customer_summary` | Flat, with `withheld_fields` | A nested object costs more and suggests a precision that isn't needed |
| `add_case_comment`, `escalate_case` | No `If-Match` or `Idempotency-Key`, and a two-step approval | The server handles concurrency and retries. A model can't be trusted to keep an ETag, or to be the one deciding |

**Measured** (bytes of what the model reads, on the seed data; divide by about 4 for tokens):

| Scenario | REST | Tool | Saving |
|---|---|---|---|
| Sample question, if tools just mirrored REST | 4,801 over 7 calls | 909 in 1 call | 81% |
| Sample question, using the API's own filters | 1,403 | 909 | 35% |
| Page of 25 active cases | 7,803 | 5,494 | 30% |
| One customer | 312 | 231 | 26% |

The first design was larger than the API on a full page, because repeating long keys and `null` values on every row cost more than removing fields saved. Empty values are now left out, and text isn't indented. `untrusted_` stays on field names, even though it costs bytes, because it is a safety label.

The SDK's defaults needed changing. It accepts unknown arguments silently, so `escalate_case(..., approved=true)` would look like it worked. Schemas now set `additionalProperties: false`, the argument model forbids extras, and generated titles are removed.

## Identity flow
How a user's identity and scopes reach the API when an agent acts for them:

1. **The user signs in.** Their token has audience `copilot-mcp`, so it is valid only for the MCP server. The server verifies it on start.
2. **The agent calls a tool.** The agent never sees a token. For a write, the server first checks that the user holds the scope, so a request for approval can't be raised for something they couldn't do.
3. **Writes wait for a person.** The write scope isn't requested until a person has approved, outside the model's reach.
4. **The server exchanges the token per call (RFC 8693),** sending the user's token, its own client credentials, audience `platform-api`, and only the scopes this call needs. The user's token is never forwarded.
5. **The provider narrows access.** The new token gets user scopes ∩ agent ceiling ∩ requested scopes, lasts about a minute, and has `sub` = user and `act.sub` = agent. An empty result is refused.
6. **The API authorizes the user.** It checks signature, issuer, audience and expiry, then scopes, ARR per field, and accounts per resource. A malformed `act` gets a 401, so it can't pass as a person.
7. **Both identities are recorded.** `act` tokens can only write internal comments. `tool_audit` and `audit_log` hold both identities, joined by the correlation ID.

## MCP guardrails
| Decision | Why | Trade-off |
|---|---|---|
| Token exchange per tool call, with only the scopes that call needs | The spec forbids passthrough, and a token that leaks is then limited to one scope for about a minute | One extra identity provider call per tool call |
| Identity flows as `sub` (user) and `act.sub` (agent) | The API authorizes the user and records both | Depends on the provider supporting RFC 8693 |
| Approval happens outside the model's reach | A confirmation token handed to the model can simply be echoed back | A person has to act in a second place |
| Approval is bound to user, agent, tool, exact arguments and case version | Stops an approval being stretched to a different action or a changed case | A changed case means asking again |
| Approval is enforced by this server, not by client elicitation | Elicitation depends on client support, and a server can't assume it | Worse experience than an inline prompt. Worth adding once clients reliably support it |
| Write scope is only minted after approval | A bug before that point can't write, as the token doesn't exist yet | None worth noting |
| Injection handled by limiting what a fooled model can do | Detection can always be reworded around, and a test records that | `injection_suspected` is only a signal |
| Agent comments are internal only | Closes the route from injected text to a customer | An agent can't draft public replies directly |
| Limits and audit in SQLite, not memory | Stdio starts a new process per session, so in-memory limits would reset | Not shared between machines |
| Audit in middleware, before validation | Rejected and unknown calls matter most for security review, and never reach tool code | None worth noting |

**Known limits**
- **Approval runs where the model can't reach it.** If the agent also had a shell, it could run `make approve`. Production needs SSO on the approval step, and the demo gives the agent only these tools.
- **The API doesn't know about approvals.** A client holding a valid user-scoped token could call it directly, so the API needs to require approval evidence for `act` tokens, or the approval store should move behind it.
- **A resumed API escalation skips the account check.** If a user loses access to an account between an attempt and its retry, the retry still goes through. Re-check access on resume with more time.
- **A replay skips the account check too.** The same user with the same key, within 24 hours, still gets the stored response after losing access to the account. It is data they already saw. Store the account ID with the key and check it before replaying.
- **Subscriptions aren't limited by account.** Events fan out across all accounts, so a token with both an `accounts` claim and `webhooks:manage` would see every account's events. No seeded persona has both. Reject that combination, or filter fan-out by account.
- **Shutdown doesn't wait for in-flight webhook deliveries.** A cut-off delivery stays pending and is resent on the next start, which at-least-once delivery allows. Implement `stop(Runnable)` with a bounded wait.
- **A port conflict can leave the stack half-started.** `Stack` should close what started and exit non-zero.
- **Scope checks are opt-in per route.** A new `/v1` handler without `@RequiresScope` accepts any valid token, and `SecurityCoverageTest` walks the spec rather than the handlers. Deny by default, and test from the registered handlers.
- **Stdio means one user per process.** Multi-user needs Streamable HTTP with OAuth, through the gateway, as `PLATFORM.md` describes.

**Dropped from the plan:** `total_matched`, because a count costs a full scan and `has_more` answers the question. Usage trend, because the API returns one month, and adding it wasn't worth the extra contract surface.

## Live demo
| Decision | Why | Trade-off |
|---|---|---|
| A CLI LLM client connected over stdio, with the transcript committed | No API key is needed, and the brief allows a connected client | Reviewers need the CLI to re-run it, so `make demo-scripted` gives the same flow with no LLM |
| The agent gets only the four tools | With a shell, it could run the approval command itself | The isolation is in how the client is launched, not in the server |
| The demo checks outcomes in the source system and the answer's text | A model's wording varies between runs, so checking exact text would be flaky. Checking the case's real status isn't | Some checks are loose regexes over prose |
