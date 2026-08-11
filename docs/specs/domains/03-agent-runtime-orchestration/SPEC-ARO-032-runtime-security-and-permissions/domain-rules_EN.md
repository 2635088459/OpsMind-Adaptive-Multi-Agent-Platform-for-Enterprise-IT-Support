# SPEC-ARO-032 — Domain Rules

Goal: support `Runtime Security and Permissions`.

- Runtime state and Ticket state must remain separate.
- Agents must not call Tools directly.
- State transitions must validate current state, version, and idempotency key.
- Failure paths must retain auditable reasons.
