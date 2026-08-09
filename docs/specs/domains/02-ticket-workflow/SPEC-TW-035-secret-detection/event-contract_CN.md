# SPEC-TW-035 Event / Audit Contract

## Internal Record

`security.secret-detected`

```json
{
  "recordId": "audit-035",
  "recordName": "security.secret-detected",
  "ticketId": "TCK-1001",
  "actorId": "user-123",
  "operation": "ticket.command",
  "decision": "ALLOW",
  "decisionCode": "POLICY_ALLOWED",
  "occurredAt": "2026-08-08T12:00:00Z"
}
```

## 规则

- payload 必须脱敏；
- 不包含 secret pattern、JWT、raw credential、完整授权范围；
- recordId 幂等可追踪。
