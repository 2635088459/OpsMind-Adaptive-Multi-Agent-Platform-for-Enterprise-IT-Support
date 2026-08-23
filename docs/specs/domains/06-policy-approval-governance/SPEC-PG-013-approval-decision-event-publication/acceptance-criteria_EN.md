# Acceptance Criteria — SPEC-PG-013

## Functional Acceptance

- The implementation fulfills the goal: Publish approval.granted.v1, approval.denied.v1, approval.expired.v1, approval.cancelled.v1.
- All state transitions follow rules from `06-event-contracts, 08-transaction-and-outbox`.
- API, event, persistence, or worker behavior covers happy path, duplicate request, and failure path.
- No direct Tool/Ticket/Workflow/Memory side effects are introduced.

## Governance Acceptance

- Decisions persist policy version, input hash, reason codes, and constraints.
- Approval decisions validate actor permission, request linkage, and separation of duties.
- denied, expired, cancelled, and policy denied keep distinct semantics.
- Audit records explain who requested, who approved, which policy applied, and why.

## Reliability Acceptance

- Duplicate requests or duplicate events do not create conflicting final decisions.
- Outbox events are replayable with stable event ids.
- Evaluator failure and degraded mode behavior is testable.
