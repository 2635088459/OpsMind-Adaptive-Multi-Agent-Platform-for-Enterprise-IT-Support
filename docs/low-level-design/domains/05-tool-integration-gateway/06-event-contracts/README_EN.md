# 06 Event Contracts

## Consumed Events

### `approval.granted.v1`

Purpose: approve a waiting Tool Request.

Key fields:

- `eventId`
- `approvalRequestId`
- `toolRequestId`
- `ticketId`
- `workflowInstanceId`
- `approvedBy`
- `decisionAt`
- `constraints`

Only a Tool Request in `WAITING_APPROVAL` with valid approval linkage may move to `QUEUED`.

### `approval.denied.v1`

Purpose: deny a waiting Tool Request.

Gateway must publish `tool.completed.v1` with status `DENIED` so Runtime can resume from waiting state.

### `policy.rule.changed.v1`

Purpose: refresh capability risk, connector enablement, network allowlist, and approval requirement cache.

It must not retroactively change completed executions, but it may affect requests that have not executed yet.

### `workflow.cancelled.v1`

Purpose: when Runtime workflow is cancelled, Gateway attempts to cancel associated pending/running Tool Requests.

## Published Events

### `tool.request.accepted.v1`

Published after Tool Request validation succeeds and request is persisted.

### `tool.request.rejected.v1`

Published when request is rejected due to schema, capability, permission, or idempotency conflict.

### `tool.approval.required.v1`

Published when approval is required, so domain 06 can create or link an approval request.

### `tool.execution.started.v1`

Published when a worker claims an execution and is about to invoke the connector.

### `tool.execution.retry_scheduled.v1`

Published after retryable failure schedules another attempt.

### `tool.completed.v1`

Published after Tool Request reaches a final outcome. Runtime depends on this event to resume workflows waiting for tool results.

Payload:

```json
{
  "toolRequestId": "trq-123",
  "executionId": "tex-123",
  "ticketId": "ticket-123",
  "ticketCycleId": "cycle-1",
  "workflowInstanceId": "wf-123",
  "agentTaskId": "task-456",
  "capabilityName": "kubernetes.getPodLogs",
  "connectorId": "k8s-logs",
  "status": "SUCCEEDED",
  "summary": "Fetched 200 log lines for checkout pods.",
  "structuredOutput": {},
  "resultEnvelopeId": "res-123",
  "evidenceRefs": ["evidence-1"],
  "redactionStatus": "REDACTED",
  "errorCode": null,
  "retryable": false,
  "occurredAt": "2026-08-17T00:00:00Z"
}
```

### `tool.connector.registered.v1`

Published after a new connector registry version is available.

### `tool.connector.health_changed.v1`

Published after connector health changes.

## Envelope

All events use the shared envelope:

```json
{
  "eventId": "uuid",
  "eventType": "tool.completed.v1",
  "producer": "tool-integration-gateway",
  "schemaVersion": 1,
  "aggregateId": "trq-123",
  "ticketId": "ticket-123",
  "correlationId": "corr-123",
  "causationId": "evt-source",
  "occurredAt": "2026-08-17T00:00:00Z",
  "payload": {}
}
```

## Idempotency

Every event consumer must deduplicate by `eventId + consumerName`. Approval events must also verify that `approvalRequestId + toolRequestId` matches stored approval linkage.

