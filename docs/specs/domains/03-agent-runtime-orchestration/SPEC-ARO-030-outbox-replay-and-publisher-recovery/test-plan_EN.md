# SPEC-ARO-030 — Test Plan

Goal: support `Outbox Replay and Publisher Recovery`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
