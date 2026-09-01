# Support Console — Observability and Audit

> **Document ID:** LLD-SC-012
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. The frontend's role matches domain 09

Likewise only propagates OpenTelemetry trace context as a starting point (the `traceparent` header), never touching LangSmith, never producing its own spans. See `09-employee-portal`'s own `12-observability-and-audit` for the full principle — identical here, not repeated.

## 2. Unique to this domain: it is itself the display surface for observability/evaluation data

Domain 09 is only one of the producers of observability data; domain 10 additionally carries the responsibility of **displaying** observability/evaluation data (the visual mockup's "Observability · Evaluation" page). This does not change the boundary in §1 — the display layer remains only external-link/read-only aggregation, not support-console itself becoming an observability system.

## 3. Audit of the agent's own actions

Every action a support agent performs in the console (triage/assign/grant/deny) produces a real audit record in the corresponding backend domain (`02-ticket-workflow`/`06-policy-approval-governance` have both already genuinely implemented this). Frontend responsibility is the same as domain 09: ensure every request carries a real `actorId` (JWT `sub`) and a `correlationId`, never missing, never fabricated.

## 4. Frontend-specific metrics worth monitoring (for future dashboards)

```text
Average latency and failure rate of queue polling
Frequency of partial AiLogEntry-aggregation failures (measures how much each backend domain's real availability actually affects agent experience)
Frequency of VERSION_CONFLICT triggers (measures how common real multi-agent collaboration conflicts actually are, informing whether phase-2 real-time push should be prioritized sooner)
```
