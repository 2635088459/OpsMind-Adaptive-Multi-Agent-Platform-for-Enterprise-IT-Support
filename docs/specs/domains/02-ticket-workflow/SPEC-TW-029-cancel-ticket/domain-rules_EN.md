# SPEC-TW-029 Domain Rules

- Allowed source state: `non-terminal mutable states`.
- Target effect: `CANCELLED`.
- Command actor: requester or authorized support actor.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- Cancel is terminal and must reject future close, reopen, assignment, escalation, and resume commands.
