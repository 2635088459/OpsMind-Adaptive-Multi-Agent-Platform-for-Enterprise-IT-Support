# SPEC-ARO-029 — Domain Rules

Goal: support `Expired Lease Task Recovery`.

- Runtime state and Ticket state must remain separate.
- Agents must not call Tools directly.
- State transitions must validate current state, version, and idempotency key.
- Failure paths must retain auditable reasons.
