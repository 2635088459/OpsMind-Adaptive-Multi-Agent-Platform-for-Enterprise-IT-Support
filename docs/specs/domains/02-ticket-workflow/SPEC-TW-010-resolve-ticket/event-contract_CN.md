# SPEC-TW-010 — 事件契约

## 1. 事件名称

```text
ticket.resolved.v1
```

该事件只能由 transactional outbox 在数据库事务提交后发布。

## 2. 通用 Envelope

```json
{
  "eventId": "a1049608-564e-4600-8b32-5134da28d8e0",
  "eventType": "ticket.resolved.v1",
  "occurredAt": "2026-07-31T19:05:00Z",
  "aggregateType": "Ticket",
  "aggregateId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "aggregateVersion": 18,
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec",
  "causationId": "8d79d912-4550-4ced-a3ed-f09c2400f05f",
  "actor": {
    "type": "IT_SUPPORT",
    "id": "sam.support"
  },
  "data": {}
}
```

当前代码库没有 tenant 概念；除非后续全局引入 tenant，否则本事件不要求 `tenantId`。

## 3. Data Payload

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "resolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "previousStatus": "IN_PROGRESS",
  "newStatus": "RESOLVED",
  "resolutionCode": "FIXED",
  "resolutionSummary": "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
  "resolvedBy": "sam.support",
  "resolvedAt": "2026-07-31T19:05:00Z"
}
```

## 4. Delivery Semantics

- at-least-once delivery；
- `eventId` 是消费者去重 key；
- partition key 是 `aggregateId`；
- ordering 由 `aggregateVersion` 解释；
- schema evolution 在 v1 内只允许 additive change；breaking change 必须升 v2。

## 5. 隐私与安全

事件不得包含 token、email、原始 claims、私密 Ticket message、审批详情、工具执行日志、队列成员证明或完整身份资料。

`resolutionSummary` 可以包含面向 requester 的解决摘要，但不得包含 secret、password、access token、private key 或完整日志。

## 6. Consumer Expectations

消费者必须幂等处理，忽略已处理 `eventId`，将 malformed event 交给自己的 DLQ 策略，且不得用旧 aggregate version 覆盖新 projection。

## 7. Observability

Outbox 与 publisher telemetry 包含 event type、event ID、aggregate ID/version、correlation ID、attempt count 和 outcome。Metric label 不包含 summary、actor ID 或 idempotency key。
