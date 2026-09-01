# SPEC-ARO-039 — Acceptance Criteria

Goal: support `Inline Message Turn Execution`.

- A real call to `04-memory-knowledge` genuinely happens for every message turn — never silently skipped.
- The response is always exactly one of the three declared shapes; no fourth, ambiguous shape is ever returned.
- Replaying the same `Idempotency-Key` returns the original response without re-invoking the LLM or knowledge retrieval a second time.
- A checkpoint exists for every turn, recoverable per the existing checkpoint-restore mechanism (SPEC-ARO-028).
