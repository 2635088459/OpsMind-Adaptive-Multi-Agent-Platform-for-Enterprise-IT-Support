# SPEC-TW-028 Test Plan

## Unit Tests

- state machine guard allows `RESOLVED or CLOSED` and rejects invalid states;
- reason/actor/idempotency validation;
- duplicate command returns the first result;
- stale expectedVersion returns conflict.

## Integration Tests

- aggregate, audit table, and outbox event commit consistently;
- outbox relay publishes `ticket.reopened.v1`;
- unauthorized actors produce no mutation;
- terminal state commands are rejected.

## Regression Tests

- Phase 06 tool execution remains unchanged;
- Phase 07 verification/resolution cycle remains unchanged;
- timeline can render the Phase 8 audit event.
