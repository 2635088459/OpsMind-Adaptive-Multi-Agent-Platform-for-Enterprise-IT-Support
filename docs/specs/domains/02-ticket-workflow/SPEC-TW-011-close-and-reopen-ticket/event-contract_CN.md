# SPEC-TW-011 — 事件契约

## 1. Event Types

```text
ticket.closed.v1
ticket.reopened.v1
```

事件只能由 transactional outbox 在业务事务提交后发布。

## 2. ticket.closed.v1

Payload 示例：

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "resolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "previousStatus": "RESOLVED",
  "newStatus": "CLOSED",
  "closeReasonCode": "REQUESTER_CONFIRMED",
  "closedBy": "sam.support",
  "closedAt": "2026-07-31T20:10:00Z"
}
```

## 3. ticket.reopened.v1

Payload 示例：

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "previousResolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "newResolutionCycleId": "b2b0eb44-aecf-4e4d-a77a-2b09d9eab2e8",
  "previousStatus": "CLOSED",
  "newStatus": "IN_PROGRESS",
  "reopenReasonCode": "ISSUE_RECURRED",
  "reopenCount": 1,
  "reopenedBy": "sam.support",
  "reopenedAt": "2026-07-31T21:30:00Z",
  "ownershipStatus": "ACTIVE"
}
```

## 4. Envelope

沿用 Phase 03 event envelope：

- `eventId`
- `eventType`
- `occurredAt`
- `aggregateType = Ticket`
- `aggregateId`
- `aggregateVersion`
- `correlationId`
- `causationId`
- `actor`
- `data`

当前代码库没有 tenant 概念；除非全局引入 tenant，否则本事件不要求 `tenantId`。

## 5. Delivery Semantics

- at-least-once delivery；
- `eventId` 是消费者去重 key；
- partition key 是 `aggregateId`；
- ordering 由 `aggregateVersion` 解释；
- breaking change 必须升 v2。

## 6. 隐私

事件不得包含 token、email、原始 claims、完整 reason 文本、私密 message、审批详情、工具日志或完整身份资料。Reason 只允许通过 code 和经过安全处理的短摘要进入 timeline，不进入 metric label。
