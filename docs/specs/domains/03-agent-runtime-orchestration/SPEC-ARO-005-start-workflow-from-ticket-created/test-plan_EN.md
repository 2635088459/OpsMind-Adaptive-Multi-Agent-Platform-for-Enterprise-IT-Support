# SPEC-ARO-005 — Test Plan

Goal: support `Start Workflow From ticket.created`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
