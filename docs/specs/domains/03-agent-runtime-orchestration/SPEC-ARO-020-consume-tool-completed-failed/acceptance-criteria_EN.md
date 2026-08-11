# SPEC-ARO-020 — Acceptance Criteria

Goal: support `Consume tool.completed and tool.failed`.

- Docs, code, migration, and tests can close at single-spec granularity.
- Mapped LLD rules are covered.
- Idempotency, concurrency, and failure paths have tests.
- No Agent direct Tool call or Runtime direct Ticket state write is introduced.
