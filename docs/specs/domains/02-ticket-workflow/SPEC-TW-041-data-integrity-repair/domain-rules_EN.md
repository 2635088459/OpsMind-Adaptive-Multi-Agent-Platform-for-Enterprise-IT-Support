# SPEC-TW-041 Domain Rules

- Phase 10 recovery introduces no new business happy path.
- Repair must first produce a scan finding and repair plan before controlled repair execution.
- Recovery runs through dedicated commands/use cases; controllers, schedulers, and consumers cannot update entities directly.
- Every action binds to a case/attempt or source event.
- Repair does not justify bypassing authorization, audit, idempotency, or outbox.
