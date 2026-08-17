# 05 API Contracts

## Runtime API

### POST `/tool-requests`

用途：由 Agent Runtime 创建 Tool Request。

请求：

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

响应：

```json
{
  "toolRequestId": "trq-123",
  "status": "QUEUED",
  "requiresApproval": false,
  "approvalRequestId": null,
  "acceptedAt": "2026-08-17T00:00:00Z"
}
```

幂等语义：

- 相同 `idempotencyKey + workflowInstanceId + agentTaskId` 返回同一个 Tool Request。
- 如果 payload hash 不同，返回 `409 IDEMPOTENCY_CONFLICT`。

### GET `/tool-requests/{toolRequestId}`

用途：Runtime 或管理端查询请求状态。

返回 ToolRequest summary，不包含 secret 或 raw output。

### POST `/tool-requests/{toolRequestId}/cancel`

用途：取消未执行或正在执行的请求。

必须提供 `idempotencyKey` 和 requester。

## Connector Admin API

### POST `/connectors`

注册 connector manifest。

### PATCH `/connectors/{connectorId}/status`

启用、停用、废弃 connector。

### GET `/capabilities`

返回 Runtime 可见的 capability registry。返回结果必须按 tenant、actor、policy visibility 过滤。

## Result API

### GET `/tool-results/{resultEnvelopeId}`

返回脱敏结果。

### GET `/tool-results/{resultEnvelopeId}/raw`

受控读取 raw output。必须通过高权限 RBAC、audit reason 和 policy check。

## Internal Worker API

内部 worker 不通过公开 HTTP API 修改状态；它们通过 application service + repository 在同一服务内执行事务。若未来拆成独立 worker 服务，必须使用 mTLS + service identity。

## 错误模型

标准错误：

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

所有错误响应都必须包含 `correlationId` 和可审计 error code。

