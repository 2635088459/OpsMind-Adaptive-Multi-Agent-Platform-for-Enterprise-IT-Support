# SPEC-TW-041 Acceptance Criteria

## Functional Acceptance

- Given a recovery command that satisfies preconditions, the system produces a controlled recovery result and records `ticket.integrity-repair-applied.v1`.
- Given a duplicate command, the system returns the first result without repeating side effects.
- Given stale or illegal state, the system rejects and records an observable decision.

## Security and Audit

- actor, reason, correlationId, and causationId are required or traceable.
- Rejected paths do not publish success events.
- Audit payloads contain no secrets, tokens, or high-cardinality fields.

## Regression Acceptance

- Phase 01 to Phase 09 golden paths remain intact.
- outbox, idempotency, audit, and state-machine guards keep their atomic boundaries.
