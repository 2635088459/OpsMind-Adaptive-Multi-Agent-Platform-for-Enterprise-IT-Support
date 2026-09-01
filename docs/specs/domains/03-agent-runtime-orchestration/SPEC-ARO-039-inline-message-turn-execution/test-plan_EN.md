# SPEC-ARO-039 — Test Plan

Goal: support `Inline Message Turn Execution`.

- Unit tests for the three-way response discriminator, including the boundary between "escalation" and "proposed action" decisions.
- Integration test with a real `04-memory-knowledge-service` call (against the real docker-compose stack).
- Idempotency-replay test: the same key resubmitted does not trigger a second knowledge-retrieval or LLM call (verified via a call-count assertion on a test double or real call log).
- A recovery test: a crash after the checkpoint write but before the response is returned can be recovered from the checkpoint (reusing SPEC-ARO-028's existing recovery-scanner test pattern).
