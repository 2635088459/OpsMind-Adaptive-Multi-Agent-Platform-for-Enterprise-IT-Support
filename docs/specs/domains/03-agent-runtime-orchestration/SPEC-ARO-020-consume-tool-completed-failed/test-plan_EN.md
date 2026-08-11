# SPEC-ARO-020 — Test Plan

Goal: support `Consume tool.completed and tool.failed`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
