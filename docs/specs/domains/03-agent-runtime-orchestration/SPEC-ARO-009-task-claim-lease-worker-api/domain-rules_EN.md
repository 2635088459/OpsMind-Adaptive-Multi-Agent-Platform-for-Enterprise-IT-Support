# SPEC-ARO-009 — Domain Rules

Goal: support `Task Claim Lease Worker API`.

- Runtime state and Ticket state must remain separate.
- Agents must not call Tools directly.
- State transitions must validate current state, version, and idempotency key.
- Failure paths must retain auditable reasons.
