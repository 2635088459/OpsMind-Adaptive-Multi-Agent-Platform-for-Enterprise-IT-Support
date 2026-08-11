# 06 Event Contracts

## Consumed Events

Runtime subscribes to external domain events but treats them only as orchestration inputs.

### ticket.created.v1

Purpose: create and start the initial Workflow Instance.

Key fields:

- `eventId`
- `ticketId`
- `ticketCycleId`
- `priority`
- `category`
- `createdBy`
- `occurredAt`

Idempotency key: `eventId` or `ticketId + ticketCycleId + workflowType`.

### approval.granted.v1

Purpose: resume a Workflow waiting for approval.

Key fields:

- `approvalRequestId`
- `ticketId`
- `workflowInstanceId`
- `decision`
- `approvedBy`
- `occurredAt`

Effective only when workflow is in `WAITING_FOR_APPROVAL`.

### tool.completed.v1

Purpose: resume a Workflow waiting for tool result.

Key fields:

- `toolRequestId`
- `gatewayCorrelationId`
- `workflowInstanceId`
- `agentTaskId`
- `status`
- `resultPayload`
- `occurredAt`

Must match a persisted Runtime Tool Request.

### verification.completed.v1

Purpose: resume a Workflow waiting for verification result.

Key fields:

- `verificationRequestId`
- `workflowInstanceId`
- `ticketId`
- `passed`
- `evidence`
- `occurredAt`

When verification fails, Runtime may create remediation tasks.

## Published Events

All published events must be written to Runtime outbox.

### workflow.started.v1

Published after Workflow Instance starts and initial task graph is written.

### workflow.paused.v1

Published after Workflow enters `PAUSED` and checkpoint has been written.

### workflow.resumed.v1

Published after Workflow resumes to `RUNNING` or `WAITING_*`.

### agent.task.completed.v1

Published after Agent Task enters `COMPLETED`. Event contains result summary, not sensitive raw context.

### workflow.completed.v1

Published when Runtime automation completes. Ticket closure is decided by Ticket Workflow.

### workflow.failed.v1

Published when Runtime cannot recover automatically, for Ticket Workflow or human operations.

## Envelope

All events use a common envelope:

```json
{
  "eventId": "evt-123",
  "eventType": "workflow.started.v1",
  "aggregateId": "wf-123",
  "ticketId": "ticket-123",
  "correlationId": "corr-123",
  "causationId": "evt-source",
  "occurredAt": "2026-08-10T00:00:00Z",
  "payload": {}
}
```
