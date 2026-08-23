# 05 API Contracts

## Decision API

### POST `/policy-decisions:evaluate`

Request:

```json
{
  "decisionKey": "tool-request-123:risk:v1",
  "subject": {"type": "AGENT", "id": "triage-agent"},
  "action": {"type": "TOOL_EXECUTE", "name": "kubernetes.restartDeployment"},
  "resource": {"type": "TOOL_CAPABILITY", "id": "kubernetes.restartDeployment"},
  "tenantId": "tenant-1",
  "ticketId": "ticket-123",
  "workflowInstanceId": "wf-123",
  "sourceRequestId": "trq-123",
  "inputHash": "sha256..."
}
```

Response:

```json
{
  "policyDecisionId": "pd-123",
  "effect": "REQUIRE_APPROVAL",
  "riskLevel": "HIGH",
  "approvalRequired": true,
  "constraints": [{"type": "TIME_WINDOW", "value": "business-hours"}],
  "reasonCodes": ["HIGH_RISK_MUTATION"],
  "policyVersion": 4
}
```

## Approval API

- `POST /approval-requests`
- `GET /approval-requests/{approvalRequestId}`
- `POST /approval-requests/{approvalRequestId}:grant`
- `POST /approval-requests/{approvalRequestId}:deny`
- `POST /approval-requests/{approvalRequestId}:cancel`

Every command must include idempotency key, actor, reason, and correlation id.

## Admin Policy API

- `POST /policies`
- `POST /policies/{policyId}/versions`
- `POST /policies/{policyId}/versions/{version}:publish`
- `POST /policies/{policyId}/versions/{version}:deprecate`

Policy publish requires reviewer/publisher separation of duties.

## Audit API

- `GET /governance-audit?ticketId=...`
- `GET /governance-audit?sourceRequestId=...`
- `GET /policy-decisions/{policyDecisionId}`

Audit API returns governance metadata by default and does not return sensitive input.

