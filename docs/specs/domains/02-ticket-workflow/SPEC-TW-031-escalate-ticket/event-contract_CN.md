# SPEC-TW-031 Event Contract

## Published Event

`ticket.escalated.v1`

```json
{
  "eventId": "evt-031",
  "eventName": "ticket.escalated.v1",
  "ticketId": "TCK-1001",
  "fromState": "mutable non-terminal states",
  "toState": "ESCALATED",
  "workflowVersion": 43,
  "actorId": "user-123",
  "auditId": "audit-031",
  "occurredAt": "2026-08-06T12:00:00Z"
}
```

## 事件规则

- 事件只在事务提交后通过 outbox 发布；
- payload 不携带敏感 free-text 原文以外的凭据；
- consumer 必须按 eventId 幂等消费。
