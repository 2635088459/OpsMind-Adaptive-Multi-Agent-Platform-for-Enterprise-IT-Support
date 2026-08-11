# SPEC-ARO-024 — Event Contract

Goal: support `Duplicate Stale Invalid Event Classification`.

- Consumed events must validate event id, producer, schema version, and correlation.
- Published events must go through Runtime outbox.
- Event envelope must include correlationId, causationId, ticketId, and workflowInstanceId.
- Duplicate/stale/invalid events must not advance Workflow again.
