# Acceptance Criteria — SPEC-TG-028

## Functional Acceptance

- The implementation fulfills the goal: Implement dead-letter outbox, poison request, and controlled admin replay/repair.
- All state transitions follow rules from `08-transaction-and-outbox, 10-failure-handling`.
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
