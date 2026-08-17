# 05 API Contracts

## Runtime API

### POST `/tool-requests`

Purpose: Agent Runtime creates a Tool Request.

Request:

```json
{
  "idempotencyKey": "wf-123:task-456:kubernetes.getPodLogs:v1",
  "ticketId": "ticket-123",
  "ticketCycleId": "cycle-1",
  "workflowInstanceId": "wf-123",
  "agentTaskId": "task-456",
  "requestedBy": {
    "type": "AGENT",
    "id": "triage-agent"
  },
  "capabilityName": "kubernetes.getPodLogs",
  "toolName": "optional-specific-tool",
  "input": {
    "namespace": "prod",
    "podSelector": "app=checkout"
  },
  "reason": "Need logs to diagnose checkout 5xx spike.",
  "contextRefs": ["memory-result-1"]
}
```

Response:

```json
{
  "toolRequestId": "trq-123",
  "status": "QUEUED",
  "requiresApproval": false,
  "approvalRequestId": null,
  "acceptedAt": "2026-08-17T00:00:00Z"
}
```

Idempotency semantics:

- Same `idempotencyKey + workflowInstanceId + agentTaskId` returns the same Tool Request.
- If payload hash differs, return `409 IDEMPOTENCY_CONFLICT`.

### GET `/tool-requests/{toolRequestId}`

Purpose: Runtime or admin queries request status.

Returns ToolRequest summary, excluding secrets and raw output.

### POST `/tool-requests/{toolRequestId}/cancel`

Purpose: cancel a pending or executing request.

Requires `idempotencyKey` and requester.

## Connector Admin API

### POST `/connectors`

Register a connector manifest.

### PATCH `/connectors/{connectorId}/status`

Enable, disable, or deprecate a connector.

### GET `/capabilities`

Return capability registry visible to Runtime. Results must be filtered by tenant, actor, and policy visibility.

## Result API

### GET `/tool-results/{resultEnvelopeId}`

Return redacted result.

### GET `/tool-results/{resultEnvelopeId}/raw`

Controlled raw output access. Requires privileged RBAC, audit reason, and policy check.

## Internal Worker API

Internal workers do not mutate state through public HTTP APIs. They use application services and repositories inside the same service transaction. If workers are split into separate services later, they must use mTLS and service identity.

## Error Model

Standard errors:

- `VALIDATION_FAILED`
- `CAPABILITY_NOT_FOUND`
- `CONNECTOR_UNAVAILABLE`
- `POLICY_DENIED`
- `APPROVAL_REQUIRED`
- `APPROVAL_DENIED`
- `IDEMPOTENCY_CONFLICT`
- `EXECUTION_TIMEOUT`
- `CONNECTOR_FAILED`
- `PARTIAL_SIDE_EFFECT_UNCERTAIN`
- `RAW_OUTPUT_FORBIDDEN`

Every error response must include `correlationId` and auditable error code.

