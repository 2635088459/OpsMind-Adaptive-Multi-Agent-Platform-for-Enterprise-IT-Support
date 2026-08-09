# SPEC-TW-033 Event / Audit Contract

## Internal Record

`audit.authorization-denied-recorded`

```json
{
  "recordId": "audit-033",
  "recordName": "audit.authorization-denied-recorded",
  "ticketId": "TCK-1001",
  "actorId": "user-123",
  "operation": "ticket.command",
  "decision": "ALLOW",
  "decisionCode": "POLICY_ALLOWED",
  "occurredAt": "2026-08-08T12:00:00Z"
}
```

## Rules

- Payload must be redacted.
- Do not include secret patterns, JWTs, raw credentials, or full authorization scope.
- recordId is idempotent and traceable.
