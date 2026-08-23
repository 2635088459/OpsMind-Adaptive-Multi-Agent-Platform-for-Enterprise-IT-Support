# Acceptance Criteria — SPEC-PG-023

## Functional Acceptance

- The implementation fulfills the goal: Support 02 Ticket Workflow approvals for SLA exception, closure override, and escalation exception.
- All state transitions follow rules from `04-use-cases, 06-event-contracts`.
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
