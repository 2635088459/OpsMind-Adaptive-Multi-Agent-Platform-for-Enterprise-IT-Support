# SPEC-TW-038 Event Contract

## Published / Recorded Event

`ticket.event-replay-recorded.v1`

```json
{
  "eventId": "evt-038",
  "eventName": "ticket.event-replay-recorded.v1",
  "ticketId": "TCK-1001",
  "recoveryId": "recovery-038",
  "sourceReference": "event-or-case-id",
  "decision": "APPLIED",
  "occurredAt": "2026-08-09T12:00:00Z"
}
```

## Rules

- eventId is idempotent.
- Payload is redacted.
- correction/replay/compensation references sourceReference.
