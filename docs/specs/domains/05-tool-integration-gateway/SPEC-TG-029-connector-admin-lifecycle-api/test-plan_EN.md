# Test Plan — SPEC-TG-029

## Unit Tests

- domain state transition / rule validation;
- idempotency conflict;
- forbidden paths do not change state;
- error code to final status mapping.

## Integration Tests

- PostgreSQL persistence and unique keys;
- outbox write and duplicate publication;
- processed event deduplication;
- worker/API/fake connector happy path and failure path.

## Contract Tests

- API/event shape with 03 Runtime;
- approval/risk shape with 06 Policy/Approval;
- redacted evidence shape with 04 Memory Knowledge;
- traceability with 02 Ticket Workflow without direct Ticket state transition.

## Security Tests

- secrets/raw output do not appear in logs, events, responses, or memory payloads;
- unauthorized actor is rejected;
- approval-required capability cannot execute without approval.
