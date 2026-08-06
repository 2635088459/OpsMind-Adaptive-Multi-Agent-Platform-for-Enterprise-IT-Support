# SPEC-TW-027 Event Contract

## Published Event

`ticket.auto-closed.v1`

```json
{
  "eventId": "evt-027",
  "eventName": "ticket.auto-closed.v1",
  "ticketId": "TCK-1001",
  "fromState": "RESOLVED",
  "toState": "CLOSED",
  "workflowVersion": 43,
  "actorId": "user-123",
  "auditId": "audit-027",
  "occurredAt": "2026-08-06T12:00:00Z"
}
```

## 事件规则

- 事件只在事务提交后通过 outbox 发布；
- payload 不携带敏感 free-text 原文以外的凭据；
- consumer 必须按 eventId 幂等消费。
