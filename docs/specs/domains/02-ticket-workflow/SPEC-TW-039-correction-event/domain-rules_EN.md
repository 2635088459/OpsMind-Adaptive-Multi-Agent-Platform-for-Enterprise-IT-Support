# SPEC-TW-039 Domain Rules

- Phase 10 recovery introduces no new business happy path.
- Correction events must not delete or rewrite original events.
- Recovery runs through dedicated commands/use cases; controllers, schedulers, and consumers cannot update entities directly.
- Every action binds to a case/attempt or source event.
- Repair does not justify bypassing authorization, audit, idempotency, or outbox.
