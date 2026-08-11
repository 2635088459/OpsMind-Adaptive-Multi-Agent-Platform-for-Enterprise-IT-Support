# 06 Event Contracts

## 消费事件

Runtime 订阅外部领域事件，但只把它们作为编排输入。

### ticket.created.v1

用途：创建并启动初始 Workflow Instance。

关键字段：

- `eventId`
- `ticketId`
- `ticketCycleId`
- `priority`
- `category`
- `createdBy`
- `occurredAt`

幂等键：`eventId` 或 `ticketId + ticketCycleId + workflowType`。

### approval.granted.v1

用途：恢复等待审批的 Workflow。

关键字段：

- `approvalRequestId`
- `ticketId`
- `workflowInstanceId`
- `decision`
- `approvedBy`
- `occurredAt`

仅当 workflow 处于 `WAITING_FOR_APPROVAL` 时生效。

### tool.completed.v1

用途：恢复等待工具结果的 Workflow。

关键字段：

- `toolRequestId`
- `gatewayCorrelationId`
- `workflowInstanceId`
- `agentTaskId`
- `status`
- `resultPayload`
- `occurredAt`

必须匹配 Runtime 已持久化的 Tool Request。

### verification.completed.v1

用途：恢复等待验证结果的 Workflow。

关键字段：

- `verificationRequestId`
- `workflowInstanceId`
- `ticketId`
- `passed`
- `evidence`
- `occurredAt`

verification failed 时可创建 remediation task。

## 发布事件

所有发布事件必须写入 Runtime outbox。

### workflow.started.v1

在 Workflow Instance 启动并写入初始 task graph 后发布。

### workflow.paused.v1

在 Workflow 进入 `PAUSED` 且 checkpoint 已写入后发布。

### workflow.resumed.v1

在 Workflow 恢复到 `RUNNING` 或 `WAITING_*` 后发布。

### agent.task.completed.v1

在 Agent Task 进入 `COMPLETED` 后发布。事件包含 result summary，不包含敏感原始上下文。

### workflow.completed.v1

Runtime 自动化完成时发布。Ticket 是否关闭由 Ticket Workflow 决定。

### workflow.failed.v1

Runtime 无法自动恢复时发布，供 Ticket Workflow 或人工运维处理。

## Envelope

所有事件统一 envelope：

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
