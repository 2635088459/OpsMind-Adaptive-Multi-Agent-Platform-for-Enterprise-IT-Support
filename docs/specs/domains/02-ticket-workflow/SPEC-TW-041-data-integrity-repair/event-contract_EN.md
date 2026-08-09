# SPEC-TW-041 Event Contract

## Published / Recorded Event

`ticket.integrity-repair-applied.v1`

```json
{
  "eventId": "evt-041",
  "eventName": "ticket.integrity-repair-applied.v1",
  "ticketId": "TCK-1001",
  "recoveryId": "recovery-041",
  "sourceReference": "event-or-case-id",
  "decision": "APPLIED",
  "occurredAt": "2026-08-09T12:00:00Z"
}
```

## Rules

- eventId is idempotent.
- Payload is redacted.
- correction/replay/compensation references sourceReference.
