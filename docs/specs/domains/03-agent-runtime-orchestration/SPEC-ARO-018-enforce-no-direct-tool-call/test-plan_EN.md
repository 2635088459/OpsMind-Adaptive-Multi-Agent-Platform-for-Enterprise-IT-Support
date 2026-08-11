# SPEC-ARO-018 — Test Plan

Goal: support `Enforce No Direct Tool Call`.

- Unit tests cover domain transitions and invariants.
- Integration tests cover persistence, outbox, and processed-event.
- Contract tests cover API/event schema.
- Failure tests cover duplicate, stale, retry, or crash window.
