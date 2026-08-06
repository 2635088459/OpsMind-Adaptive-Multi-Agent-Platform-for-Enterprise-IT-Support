# SPEC-TW-026 Domain Rules

- Allowed source state: `RESOLVED`.
- Target effect: `CLOSED`.
- Command actor: employee or authorized support actor.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- Confirmation must reference the current resolution cycle and cannot close stale or superseded evidence.
