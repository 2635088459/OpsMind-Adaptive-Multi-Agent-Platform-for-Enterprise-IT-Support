# SPEC-TW-032 Domain Rules

- Allowed source state: `ESCALATED`.
- Target effect: `IN_PROGRESS`.
- Command actor: support lead or escalation owner.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- Resume must select a next owner/queue and cannot discard the escalation resolution notes.
