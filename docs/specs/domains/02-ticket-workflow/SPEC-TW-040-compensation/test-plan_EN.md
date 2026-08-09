# SPEC-TW-040 Test Plan

## Unit Tests

- recovery precondition allow/deny;
- duplicate idempotency replay;
- stale source reference rejection;
- audit payload redaction.

## Integration Tests

- transaction/outbox/idempotency consistency;
- DLQ/replay/correction/compensation/integrity-repair scenarios covered by this SPEC;
- crash-window or partial failure does not produce silent success.

## Release Gate

- golden path, recovery path, security hardening, and performance smoke pass.
