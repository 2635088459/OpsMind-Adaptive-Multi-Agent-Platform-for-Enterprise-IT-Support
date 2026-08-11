# SPEC-ARO-028 — Test Plan

Goal: support `Recovery Scanner and Checkpoint Restore`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
