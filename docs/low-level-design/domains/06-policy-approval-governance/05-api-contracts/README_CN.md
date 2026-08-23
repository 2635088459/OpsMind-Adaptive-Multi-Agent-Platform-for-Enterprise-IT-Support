# 05 API Contracts

## Decision API

### POST `/policy-decisions:evaluate`

请求：

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

响应：

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

所有 command 必须带 idempotency key、actor、reason 和 correlation id。

## Admin Policy API

- `POST /policies`
- `POST /policies/{policyId}/versions`
- `POST /policies/{policyId}/versions/{version}:publish`
- `POST /policies/{policyId}/versions/{version}:deprecate`

Policy publish 需要 reviewer/publisher 职责分离。

## Audit API

- `GET /governance-audit?ticketId=...`
- `GET /governance-audit?sourceRequestId=...`
- `GET /policy-decisions/{policyDecisionId}`

Audit API 默认只返回治理 metadata，不返回敏感 input。

