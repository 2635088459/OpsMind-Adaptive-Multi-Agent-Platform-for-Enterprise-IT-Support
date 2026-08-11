# SPEC-ARO-034 — Event Contract

Goal: support `Runtime Observability`.

- Consumed events must validate event id, producer, schema version, and correlation.
- Published events must go through Runtime outbox.
- Event envelope must include correlationId, causationId, ticketId, and workflowInstanceId.
- Duplicate/stale/invalid events must not advance Workflow again.
