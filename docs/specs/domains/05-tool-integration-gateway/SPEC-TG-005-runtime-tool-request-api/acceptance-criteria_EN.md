# Acceptance Criteria — SPEC-TG-005

## Functional Acceptance

- The implementation fulfills the goal: Implement Runtime APIs for create/query/cancel Tool Request with idempotency conflict semantics.
- All state transitions follow rules from `05-api-contracts, 09-concurrency-and-idempotency`.
- API, event, persistence, or worker behavior covers happy path, duplicate request, and failure path.
- No direct Ticket/Workflow state mutation is introduced.

## Security And Governance Acceptance

- Agents cannot bypass Gateway or see credentials.
- High-risk capabilities cannot bypass policy/approval.
- Secrets/raw output do not enter logs, events, memory, or checkpoints.
- Audit records explain who requested, why, what executed, and what happened.

## Reliability Acceptance

- Duplicate requests or duplicate events do not create duplicate external side effects.
- Failure, retry, and recovery paths are testable.
- Outbox events are replayable with stable event ids.
