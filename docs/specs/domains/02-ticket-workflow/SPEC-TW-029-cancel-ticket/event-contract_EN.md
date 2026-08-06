# SPEC-TW-029 Event Contract

## Published Event

`ticket.cancelled.v1`

```json
{
  "eventId": "evt-029",
  "eventName": "ticket.cancelled.v1",
  "ticketId": "TCK-1001",
  "fromState": "non-terminal mutable states",
  "toState": "CANCELLED",
  "workflowVersion": 43,
  "actorId": "user-123",
  "auditId": "audit-029",
  "occurredAt": "2026-08-06T12:00:00Z"
}
```

## Event Rules

- Events are published by outbox only after transaction commit.
- Payload does not carry credentials or sensitive text beyond approved audit fields.
- Consumers must process idempotently by eventId.
