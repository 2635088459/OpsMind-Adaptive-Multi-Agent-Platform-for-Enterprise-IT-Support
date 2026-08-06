# SPEC-TW-032 Event Contract

## Published Event

`ticket.escalation-resumed.v1`

```json
{
  "eventId": "evt-032",
  "eventName": "ticket.escalation-resumed.v1",
  "ticketId": "TCK-1001",
  "fromState": "ESCALATED",
  "toState": "IN_PROGRESS",
  "workflowVersion": 43,
  "actorId": "user-123",
  "auditId": "audit-032",
  "occurredAt": "2026-08-06T12:00:00Z"
}
```

## 事件规则

- 事件只在事务提交后通过 outbox 发布；
- payload 不携带敏感 free-text 原文以外的凭据；
- consumer 必须按 eventId 幂等消费。
