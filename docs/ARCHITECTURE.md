# Architecture and low-level design

How the platform fits together and how data moves through it. Each diagram's source is the matching `.mmd` file in `docs/diagrams/`. `DECISIONS.md` explains why it is built this way.

## 1. System and data flow
Apps and agents use the same API. Agents go through the MCP server, which adds guardrails and swaps the user's token for a short-lived one on every call. All upstreams and the identity provider are local mocks, and the only link between the parts is HTTP.

![System and data flow](diagrams/1-system-data-flow.png)

## 2. API components
A request passes through these layers from top to bottom. Packages are under `com.example.platform`.

![API components](diagrams/2-api-components.png)

## 3. Reading a customer, with partial failure
Entitlements and usage load at the same time, each with its own time budget. Usage is optional, so losing it degrades the response. Entitlements fall back to a snapshot, and the API returns 503 only if there is no data at all.

![Reading a customer](diagrams/3-read-customer.png)

## 4. Escalating a case, then delivering the webhook
A retry after a lost response must succeed rather than fail its own checks, so "attempted" is committed before the upstream call. The response, audit row and outbox rows commit in one transaction.

![Escalating a case](diagrams/4-write-escalate.png)

## 5. MCP identity and human approval
The model only ever holds an approval ID, which grants nothing until a person approves that exact action elsewhere. The write scope isn't requested until then.

![MCP identity and approval](diagrams/5-mcp-identity-approval.png)

**Known gap:** the API can't yet verify an approval. A client holding a valid user-scoped token could call it directly. See "Known limits" in `DECISIONS.md`.

## 6. Data model
Only data this service owns is stored; case, entitlement and usage data stay in their source systems. `approvals`, `rate_limits` and `tool_audit` live in the MCP server's own `mcp.db`, and everything else is in `platform.db`. Key columns are shown; `platform-schema.sql` and `store.py` have the full definitions, and `DECISIONS.md` explains the indexes.

![Data model](diagrams/6-data-model.png)
