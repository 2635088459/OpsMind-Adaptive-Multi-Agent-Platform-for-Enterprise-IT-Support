# Acceptance Criteria — SPEC-PG-012

## Functional Acceptance

- The implementation fulfills the goal: Implement approval expiry worker, cancel command, expired/cancelled events, and downstream-distinct semantics.
- All state transitions follow rules from `03-state-machine, 10-failure-handling, 08-transaction-and-outbox`.
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
