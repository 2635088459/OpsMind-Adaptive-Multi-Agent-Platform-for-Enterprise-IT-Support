# Support Console — Event Contracts

> **Document ID:** LLD-SC-006
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. This domain publishes no events, and consumes no real-time stream in the MVP either

Unlike domain 09, support-console's MVP period **relies entirely on REST polling** (`05-api-contracts` §4), with no SSE/RabbitMQ integration at all. This is not an oversight — it is a deliberate scope cut: a collaborative agent scenario tolerates much staler data than an employee's real-time conversation (a 15-30 second delay is entirely acceptable), so the priority is making UC-SC-01~06's core experience solid first, leaving real-time push to an explicit phase-2+ non-goal.

## 2. The expected shape of a future (phase 2+) real-time stream

```text
event: queue.ticket-added
event: queue.ticket-updated
data: {"queueId","ticketId","status","priority"}
```

Semantically corresponds to internal queue-membership changes in `02-ticket-workflow`; the exact contract will be formally defined by that domain when it adds the capability — this document is a forward-looking placeholder only, not a settled design.

## 3. Relationship to domain 09's event contracts

If both domains eventually need real-time capability, `02-ticket-workflow` should maintain **one** push mechanism (most likely one SSE gateway, fanned out at different subscription granularities: a single ticket vs. a whole queue), not build a separate one for each frontend app. This is an implementation detail left to `02-ticket-workflow`'s own roadmap to decide; this document only notes the constraint — "two consumers, one capability" — to avoid duplicate future construction.
