# 14 Testing Strategy

## Test Goals

Tests must prove:

- 06 performs no business side effects;
- policy decision is explainable, reproducible, and version-traceable;
- approval grant/deny/expire/cancel is idempotent;
- separation of duties cannot be bypassed;
- downstream can consume approval/policy events idempotently.

## Unit Tests

- policy rule evaluation;
- risk level mapping;
- approval state transition;
- separation-of-duties check;
- decision idempotency conflict;
- policy version immutability;
- override scope/expiry.

## Integration Tests

- PostgreSQL schema and unique keys;
- decision + audit + outbox in one transaction;
- concurrent approval grant/deny conflict;
- expiry worker;
- outbox replay;
- processed event deduplication.

## Contract Tests

- With 05: `tool.approval.required.v1`, `approval.granted.v1`, `approval.denied.v1`;
- With 03: workflow approval required / granted;
- With 02: ticket approval required / granted;
- With 04: retention/redaction decision shape.

## Security Tests

- requester cannot approve their own request;
- unauthorized approver is rejected;
- policy author cannot publish their own unreviewed rules;
- audit API does not leak sensitive input;
- override requires independent approval.

## Recovery Tests

- crash after outbox publish;
- crash before approval decision transaction commit;
- policy cache recovery;
- evaluator failure fails closed;
- duplicate approval events.

## Acceptance Criteria

Before phase/spec work starts, 06 LLD must prove all 14 slices are complete and can support 05 policy/approval dependency closure.

