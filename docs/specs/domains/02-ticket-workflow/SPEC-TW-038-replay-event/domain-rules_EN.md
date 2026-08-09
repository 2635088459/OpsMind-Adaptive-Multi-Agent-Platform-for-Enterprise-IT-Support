# SPEC-TW-038 Domain Rules

- Phase 10 recovery introduces no new business happy path.
- Replay must be idempotent by both original event id and replay attempt id.
- Recovery runs through dedicated commands/use cases; controllers, schedulers, and consumers cannot update entities directly.
- Every action binds to a case/attempt or source event.
- Repair does not justify bypassing authorization, audit, idempotency, or outbox.
