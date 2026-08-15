# 06 Event Contracts

## 消费事件

### `ticket.resolved.v1`

用途：触发 episodic / procedural memory candidate 抽取。

关键字段：

- `eventId`
- `ticketId`
- `ticketCycleId`
- `resolutionSummary`
- `resolvedBy`
- `resolvedAt`
- `verificationStatus`

### `ticket.closed.v1`

用途：确认 outcome，提升或降低 candidate usefulness。

关键字段：`ticketId`、`closedAt`、`closeReason`、`requesterConfirmed`。

### `workflow.completed.v1`

用途：获取 automation trace、task summaries、tool evidence refs。

关键字段：`workflowInstanceId`、`ticketId`、`ticketCycleId`、`completedAt`、`taskResults`。

### `workflow.failed.v1`

用途：记录失败经验，但默认不自动发布为 procedural memory。

### `evaluation.completed.v1`

用途：更新 memory usefulness score、retrieval precision、candidate quality。

### `policy.retention.changed.v1`

用途：重新计算 memory/document 的 retention 和 visibility。

## 发布事件

### `memory.candidate.created.v1`

候选被抽取并进入 pipeline 时发布。

### `memory.candidate.rejected.v1`

候选因 PII、证据不足、重复或冲突被拒绝时发布。

### `memory.published.v1`

active memory version 发布后发布。

### `memory.superseded.v1`

旧版本被新版本取代时发布。

### `memory.deleted.v1`

memory 或 document 被删除/不可检索后发布。

### `knowledge.document.indexed.v1`

document ingestion 完成并可检索后发布。

### `knowledge.graph.updated.v1`

graph nodes / edges 被 ingestion 或 memory publish 更新后发布。该事件用于 evaluation / observability，不用于驱动 Ticket 或 Workflow 状态。

关键字段：

- `graphUpdateId`
- `sourceType`
- `sourceId`
- `nodeCount`
- `edgeCount`
- `indexVersion`

## Envelope

所有事件使用共享 envelope：

```json
{
  "eventId": "uuid",
  "eventType": "memory.published.v1",
  "producer": "memory-knowledge-service",
  "schemaVersion": 1,
  "aggregateId": "memory-id",
  "ticketId": "optional-ticket-id",
  "correlationId": "uuid",
  "causationId": "uuid",
  "occurredAt": "2026-08-10T00:00:00Z",
  "payload": {}
}
```

## 幂等

所有 consumer 必须用 `eventId + consumerName` 去重。对 ticket/workflow 事件，candidate extraction 还必须使用 `sourceHash + memoryType` 防止不同 eventId 重复创建候选。
