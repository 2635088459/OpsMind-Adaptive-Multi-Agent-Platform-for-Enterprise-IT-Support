# SPEC-TW-008 — 事件契约

## 1. 事件名称

```text
ticket.assigned.v1
ticket.reassigned.v1
ticket.unassigned.v1
```

它们是 Integration Events。只能在数据库事务提交后，由 Transactional Outbox 发布。

## 2. 通用 Envelope

```json
{
  "eventId": "a1049608-564e-4600-8b32-5134da28d8e0",
  "eventType": "ticket.assigned.v1",
  "occurredAt": "2026-07-29T19:15:00Z",
  "tenantId": "32a464b7-57f8-43bd-be7e-6ebaa041c730",
  "aggregateType": "Ticket",
  "aggregateId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "aggregateVersion": 13,
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec",
  "causationId": "8d79d912-4550-4ced-a3ed-f09c2400f05f",
  "actor": {
    "type": "USER",
    "id": "2ec23fb6-0e09-42d1-82aa-dda587bfa912"
  },
  "data": {}
}
```

## 3. Assigned Data

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "previousStatus": "TRIAGED",
  "newStatus": "ASSIGNED",
  "reason": "Primary endpoint support owner"
}
```

## 4. Reassigned Data

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "previousAssigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "assigneeId": "98bf86d3-d709-448b-acd9-ef9ecbbc3d23",
  "status": "IN_PROGRESS",
  "reason": "Escalated to network specialist"
}
```

## 5. Unassigned Data

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "previousAssigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "previousStatus": "ASSIGNED",
  "newStatus": "TRIAGED",
  "reason": "Agent left the support rotation"
}
```

## 6. 投递语义

- 至少一次投递；
- `eventId` 是 Consumer 去重键；
- Partition Key 使用 `aggregateId`；
- 通过 `aggregateVersion` 判断顺序；
- Producer 不得在业务事务中直接发布；
- v1 内只允许向后兼容的新增，破坏性变化必须升级到 v2。

## 7. 隐私与安全

事件不得包含 Token、Email、原始 Claims、私密 Ticket Messages、Queue Membership 证明或完整 Identity Profile。Consumer 通过获得授权的数据源解析展示信息。

## 8. Consumer 要求

Consumer 必须幂等、忽略已处理的 `eventId`、按其 DLQ 策略处理非法事件，并且不得用较旧 Aggregate Version 覆盖较新的 Projection。

## 9. 可观测性

Outbox 与 Publisher Telemetry 包含 Event Type、Event ID、Aggregate ID/Version、Correlation ID、Attempt Count 与 Outcome。Metric Labels 不包含秘密或完整 Reason 文本。
