# 06 Event Contracts

## Consumed Events

### `tool.approval.required.v1`

Purpose: 05 requests approval creation or linkage.

Key fields: `toolRequestId`, `ticketId`, `workflowInstanceId`, `riskLevel`, `inputHash`, `constraints`.

### `workflow.approval.required.v1`

Purpose: 03 requests approval for workflow override, resume, or automation risk.

### `ticket.approval.required.v1`

Purpose: 02 requests approval for closure override, escalation exception, or SLA exception.

### `policy.evaluation.requested.v1`

Purpose: asynchronous policy evaluation request.

## Published Events

### `policy.decision.created.v1`

Published after PolicyDecision is persisted.

### `approval.requested.v1`

Published after ApprovalRequest is created.

### `approval.granted.v1`

Published after approval is granted. 05/03/02 depend on this event to resume waiting states.

### `approval.denied.v1`

Published after approval is denied.

### `approval.expired.v1`

Published after approval times out.

### `approval.cancelled.v1`

Published after approval is cancelled by requester or governance.

### `policy.published.v1`

Published after a new policy version is published.

## Envelope

All events use the shared envelope:

```json
{
  "eventId": "uuid",
  "eventType": "approval.granted.v1",
  "producer": "policy-approval-governance-service",
  "schemaVersion": 1,
  "aggregateId": "approval-request-id",
  "ticketId": "ticket-123",
  "correlationId": "corr-123",
  "causationId": "evt-source",
  "occurredAt": "2026-08-18T00:00:00Z",
  "payload": {}
}
```

## Idempotency

Every consumer deduplicates by `eventId + consumerName`. Approval decision events must also include `approvalRequestId`, `sourceDomain`, `sourceRequestId`, and `requestHash`.

