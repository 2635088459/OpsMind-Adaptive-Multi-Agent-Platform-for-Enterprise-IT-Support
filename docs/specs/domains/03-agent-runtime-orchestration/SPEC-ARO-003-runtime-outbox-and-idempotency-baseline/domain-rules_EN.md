# SPEC-ARO-003 — Domain Rules

Goal: support `Runtime Outbox and Idempotency Baseline`.

- Runtime state and Ticket state must remain separate.
- Agents must not call Tools directly.
- State transitions must validate current state, version, and idempotency key.
- Failure paths must retain auditable reasons.
