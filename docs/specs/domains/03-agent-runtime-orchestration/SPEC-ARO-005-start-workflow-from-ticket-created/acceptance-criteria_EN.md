# SPEC-ARO-005 — Acceptance Criteria

Goal: support `Start Workflow From ticket.created`.

- Docs, code, migration, and tests can close at single-spec granularity.
- Mapped LLD rules are covered.
- Idempotency, concurrency, and failure paths have tests.
- No Agent direct Tool call or Runtime direct Ticket state write is introduced.
