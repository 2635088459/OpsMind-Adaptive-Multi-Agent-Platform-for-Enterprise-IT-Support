# SPEC-TW-026 Event Contract

## Published Event

`ticket.resolution-confirmed.v1`

```json
{
  "eventId": "evt-026",
  "eventName": "ticket.resolution-confirmed.v1",
  "ticketId": "TCK-1001",
  "fromState": "RESOLVED",
  "toState": "CLOSED",
  "workflowVersion": 43,
  "actorId": "user-123",
  "auditId": "audit-026",
  "occurredAt": "2026-08-06T12:00:00Z"
}
```

## Event Rules

- Events are published by outbox only after transaction commit.
- Payload does not carry credentials or sensitive text beyond approved audit fields.
- Consumers must process idempotently by eventId.
