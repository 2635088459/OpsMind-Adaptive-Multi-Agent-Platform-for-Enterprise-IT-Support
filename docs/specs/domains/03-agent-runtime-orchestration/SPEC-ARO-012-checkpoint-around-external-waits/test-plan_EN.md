# SPEC-ARO-012 — Test Plan

Goal: support `Checkpoint Around External Waits`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
