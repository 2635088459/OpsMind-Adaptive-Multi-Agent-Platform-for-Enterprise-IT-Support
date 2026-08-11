# SPEC-ARO-004 — Event Contract

Goal: support `Workflow Instance Aggregate`.

- Consumed events must validate event id, producer, schema version, and correlation.
- Published events must go through Runtime outbox.
- Event envelope must include correlationId, causationId, ticketId, and workflowInstanceId.
- Duplicate/stale/invalid events must not advance Workflow again.
