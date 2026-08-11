# SPEC-ARO-030 — Acceptance Criteria

Goal: support `Outbox Replay and Publisher Recovery`.

- Docs, code, migration, and tests can close at single-spec granularity.
- Mapped LLD rules are covered.
- Idempotency, concurrency, and failure paths have tests.
- No Agent direct Tool call or Runtime direct Ticket state write is introduced.
