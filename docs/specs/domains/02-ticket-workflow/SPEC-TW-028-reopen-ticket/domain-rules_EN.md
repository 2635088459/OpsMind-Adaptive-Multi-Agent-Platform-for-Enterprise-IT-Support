# SPEC-TW-028 Domain Rules

- Allowed source state: `RESOLVED or CLOSED`.
- Target effect: `REOPENED`.
- Command actor: requester or authorized support actor.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- Reopen preserves previous evidence and starts a new work cycle before returning to IN_PROGRESS.
