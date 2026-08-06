# SPEC-TW-027 Domain Rules

- Allowed source state: `RESOLVED`.
- Target effect: `CLOSED`.
- Command actor: scheduler policy worker.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- The scheduler signal is advisory; the service recomputes eligibility under lock.
