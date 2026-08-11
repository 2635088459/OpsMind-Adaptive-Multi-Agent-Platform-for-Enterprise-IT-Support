# SPEC-ARO-024 — Test Plan

Goal: support `Duplicate Stale Invalid Event Classification`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
