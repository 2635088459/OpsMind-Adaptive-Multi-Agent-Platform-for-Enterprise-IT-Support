# 06 Event Contracts

## 消费事件

### `tool.approval.required.v1`

用途：05 请求创建或关联审批。

关键字段：`toolRequestId`、`ticketId`、`workflowInstanceId`、`riskLevel`、`inputHash`、`constraints`。

### `workflow.approval.required.v1`

用途：03 请求 workflow override、resume 或 automation risk 审批。

### `ticket.approval.required.v1`

用途：02 请求 closure override、escalation exception、SLA exception 审批。

### `policy.evaluation.requested.v1`

用途：异步 policy evaluation 请求。

## 发布事件

### `policy.decision.created.v1`

PolicyDecision 持久化后发布。

### `approval.requested.v1`

ApprovalRequest 创建后发布。

### `approval.granted.v1`

审批通过后发布。05/03/02 依赖该事件恢复等待状态。

### `approval.denied.v1`

审批拒绝后发布。

### `approval.expired.v1`

审批超时后发布。

### `approval.cancelled.v1`

审批被请求方或治理方取消后发布。

### `policy.published.v1`

新 policy version 发布后发布。

## Envelope

所有事件使用共享 envelope：

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

## 幂等

所有 consumer 用 `eventId + consumerName` 去重。Approval decision 事件还必须包含 `approvalRequestId`、`sourceDomain`、`sourceRequestId` 和 `requestHash`。

