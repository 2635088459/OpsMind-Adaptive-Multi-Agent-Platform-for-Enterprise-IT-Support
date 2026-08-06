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

## Event Rules

- Events are published by outbox only after transaction commit.
- Payload does not carry credentials or sensitive text beyond approved audit fields.
- Consumers must process idempotently by eventId.
