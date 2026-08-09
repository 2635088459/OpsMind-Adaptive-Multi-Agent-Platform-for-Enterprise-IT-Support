# SPEC-TW-040 Domain Rules

- Phase 10 recovery introduces no new business happy path.
- Compensation must select a defined action and cannot run arbitrary SQL or arbitrary state mutation.
- Recovery runs through dedicated commands/use cases; controllers, schedulers, and consumers cannot update entities directly.
- Every action binds to a case/attempt or source event.
- Repair does not justify bypassing authorization, audit, idempotency, or outbox.
