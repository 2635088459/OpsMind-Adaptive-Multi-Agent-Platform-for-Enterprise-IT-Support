# SPEC-ARO-013 — Domain Rules

Goal: support `Event Cursor and Processed Events`.

- Runtime state and Ticket state must remain separate.
- Agents must not call Tools directly.
- State transitions must validate current state, version, and idempotency key.
- Failure paths must retain auditable reasons.
