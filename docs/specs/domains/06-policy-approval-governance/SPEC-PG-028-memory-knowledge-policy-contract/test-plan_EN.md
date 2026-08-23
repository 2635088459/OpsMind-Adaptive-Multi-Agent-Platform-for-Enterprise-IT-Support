# Test Plan — SPEC-PG-028

## Unit Tests

- domain state transition / rule validation;
- decision/approval idempotency conflict;
- forbidden paths produce no business side effects;
- reason code, risk level, final status mapping.

## Integration Tests

- PostgreSQL persistence and unique keys;
- audit + outbox in one transaction;
- processed event deduplication;
- concurrent approval grant/deny;
- evaluator failure and expiry worker.

## Contract Tests

- risk/approval event shape with 05 Tool Gateway;
- workflow governance shape with 03 Runtime;
- ticket approval shape with 02 Ticket Workflow;
- policy decision shape with 04 Memory Knowledge.

## Security Tests

- unauthorized approver is rejected;
- requester cannot approve their own request;
- policy author cannot publish their own unreviewed policy;
- audit/API/log does not leak sensitive raw input;
- override must satisfy scope, expiry, and independent approval.
