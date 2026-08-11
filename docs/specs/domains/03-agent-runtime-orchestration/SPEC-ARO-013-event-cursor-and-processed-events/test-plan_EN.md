# SPEC-ARO-013 — Test Plan

Goal: support `Event Cursor and Processed Events`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
