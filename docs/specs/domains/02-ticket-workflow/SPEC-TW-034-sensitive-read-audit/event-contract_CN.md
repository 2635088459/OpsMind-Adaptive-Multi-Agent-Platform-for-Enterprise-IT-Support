# SPEC-TW-034 Event / Audit Contract

## Internal Record

`audit.sensitive-read-recorded`

```json
{
  "recordId": "audit-034",
  "recordName": "audit.sensitive-read-recorded",
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
