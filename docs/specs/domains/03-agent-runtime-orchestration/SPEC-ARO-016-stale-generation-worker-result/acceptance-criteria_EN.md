# SPEC-ARO-016 — Acceptance Criteria

Goal: support `Stale Generation Worker Result`.

- Docs, code, migration, and tests can close at single-spec granularity.
- Mapped LLD rules are covered.
- Idempotency, concurrency, and failure paths have tests.
- No Agent direct Tool call or Runtime direct Ticket state write is introduced.
