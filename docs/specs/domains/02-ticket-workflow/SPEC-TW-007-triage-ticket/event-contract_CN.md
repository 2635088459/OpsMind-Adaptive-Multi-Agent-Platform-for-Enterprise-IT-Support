# SPEC-TW-007 — 事件、Timeline 与 Audit 契约

## 1. 领域事件

```text
ticket.triaged.v1
```

Aggregate Type 为 `TICKET`。Outbox Row 必须与 Ticket Update 处于同一事务。发布语义为 At-Least-Once，Consumer 必须通过 `eventId` 去重。

## 2. Event Envelope

```json
{
  "eventId": "55555555-5555-5555-5555-555555555555",
  "eventType": "ticket.triaged.v1",
  "eventVersion": 1,
  "occurredAt": "2026-07-29T18:30:00Z",
  "producer": "ticket-workflow-service",
  "tenantId": "66666666-6666-6666-6666-666666666666",
  "correlationId": "21ae628b-f15d-47d1-a937-1be0f85d4cd1",
  "causationId": "2df4faae-9862-4ee6-bca0-a3b8a3455aa0",
  "actor": {
    "actorId": "44444444-4444-4444-4444-444444444444",
    "actorType": "USER"
  },
  "data": {
    "ticketId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "fromStatus": "OPEN",
    "toStatus": "TRIAGED",
    "categoryId": "11111111-1111-1111-1111-111111111111",
    "subcategoryId": "22222222-2222-2222-2222-222222222222",
    "priority": "HIGH",
    "supportQueueId": "33333333-3333-3333-3333-333333333333",
    "ticketVersion": 8
  }
}
```

## 3. Schema 规则

- UUID 使用标准 UUID String；
- Timestamp 使用 UTC RFC 3339；
- `eventVersion` 为 Integer，初始为 `1`；
- `subcategoryId` 可以是 `null`；
- `actorType` 为 `USER`、`SERVICE` 或 `SYSTEM`；
- v1 内只能增加兼容字段；破坏性变更必须使用新 Event Version/Name；
- 不包含 Requester Message Body、Token 或 Secret。

## 4. Outbox Metadata

建议值：

| 字段 | 值 |
|---|---|
| Aggregate Type | `TICKET` |
| Aggregate ID | Ticket ID |
| Event Type | `ticket.triaged.v1` |
| Partition Key | Ticket ID |
| Content Type | `application/json` |
| Status | `PENDING` |

只要求同一 Ticket 内有序，不要求全局有序。

## 5. Timeline Entry

```json
{
  "type": "TICKET_TRIAGED",
  "visibility": "INTERNAL",
  "actorId": "44444444-4444-4444-4444-444444444444",
  "occurredAt": "2026-07-29T18:30:00Z",
  "summary": "Ticket triaged to Network Support with HIGH priority.",
  "metadata": {
    "fromStatus": "OPEN",
    "toStatus": "TRIAGED",
    "categoryId": "11111111-1111-1111-1111-111111111111",
    "subcategoryId": "22222222-2222-2222-2222-222222222222",
    "priority": "HIGH",
    "supportQueueId": "33333333-3333-3333-3333-333333333333"
  }
}
```

Timeline Summary 必须通过确定性逻辑生成，或安全捕获写入时的 Catalog Display Name，不能依赖 LLM。

## 6. Audit Record

Audit Action：`ticket.triage`。

允许的 Before/After 字段：

```text
status
categoryId
subcategoryId
priority
supportQueueId
triagedBy
triagedAt
version
```

Audit 还保存 Actor、Tenant、Correlation ID、Source Identity Type 和 Result。不能保存原始 Authorization Header 或完整 Request Body。

## 7. Consumer 预期

以后 Consumer 可以更新 Search、Analytics、Notification 或 SLA Projection，但 Consumer 失败不能回滚已提交的分诊。Consumer 必须：

- 使用 `eventId` 去重；
- 容忍新增字段；
- 保证同一 Ticket 内顺序；
- 将不支持的 Event Version 交给运维处理，不能静默误读。

