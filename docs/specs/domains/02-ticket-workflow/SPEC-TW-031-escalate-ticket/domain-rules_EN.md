# SPEC-TW-031 Domain Rules

- Allowed source state: `mutable non-terminal states`.
- Target effect: `ESCALATED`.
- Command actor: support actor, policy worker, or failure handler.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- Escalation freezes automated progression until an explicit resume or cancel command.
