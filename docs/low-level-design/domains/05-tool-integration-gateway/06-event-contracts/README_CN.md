# 06 Event Contracts

## 消费事件

### `approval.granted.v1`

用途：批准等待中的 Tool Request。

关键字段：

- `eventId`
- `approvalRequestId`
- `toolRequestId`
- `ticketId`
- `workflowInstanceId`
- `approvedBy`
- `decisionAt`
- `constraints`

只有匹配 `WAITING_APPROVAL` 且 approval linkage 有效的 Tool Request 才能进入 `QUEUED`。

### `approval.denied.v1`

用途：拒绝等待中的 Tool Request。

Gateway 必须发布 `tool.completed.v1`，status 为 `DENIED`，让 Runtime 能恢复等待状态。

### `policy.rule.changed.v1`

用途：刷新 capability risk、connector enablement、network allowlist、approval requirement cache。

不得 retroactively 改变已完成 execution，但可以影响尚未执行的 request。

### `workflow.cancelled.v1`

用途：Runtime workflow 被取消时，Gateway 尝试取消关联的 pending/running Tool Request。

## 发布事件

### `tool.request.accepted.v1`

Tool Request 校验通过并持久化后发布。

### `tool.request.rejected.v1`

请求因 schema、capability、permission、idempotency conflict 被拒绝时发布。

### `tool.approval.required.v1`

需要审批时发布，供 06 创建或关联 approval request。

### `tool.execution.started.v1`

Worker claim execution 并即将调用 connector 时发布。

### `tool.execution.retry_scheduled.v1`

可重试失败后调度下一次 attempt 时发布。

### `tool.completed.v1`

Tool Request 进入 final outcome 后发布。Runtime 依赖该事件从等待工具结果的状态恢复。

payload：

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

Connector registry 新版本可用后发布。

### `tool.connector.health_changed.v1`

Connector health 状态变化后发布。

## Envelope

所有事件使用共享 envelope：

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

## 幂等

所有 event consumer 必须用 `eventId + consumerName` 去重。对 approval 事件，还必须校验 `approvalRequestId + toolRequestId` 与 linkage 匹配。

