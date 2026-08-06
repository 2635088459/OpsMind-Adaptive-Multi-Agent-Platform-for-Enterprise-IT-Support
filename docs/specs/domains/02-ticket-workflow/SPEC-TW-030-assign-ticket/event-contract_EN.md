# SPEC-TW-030 Event Contract

## Published Event

`ticket.assigned.v1`

```json
{
  "eventId": "evt-030",
  "eventName": "ticket.assigned.v1",
  "ticketId": "TCK-1001",
  "fromState": "mutable non-terminal states",
  "toState": "same lifecycle state",
  "workflowVersion": 43,
  "actorId": "user-123",
  "auditId": "audit-030",
  "occurredAt": "2026-08-06T12:00:00Z"
}
```

## Event Rules

- Events are published by outbox only after transaction commit.
- Payload does not carry credentials or sensitive text beyond approved audit fields.
- Consumers must process idempotently by eventId.
