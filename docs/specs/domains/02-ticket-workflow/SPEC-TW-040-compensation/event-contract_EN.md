# SPEC-TW-040 Event Contract

## Published / Recorded Event

`ticket.compensation-executed.v1`

```json
{
  "eventId": "evt-040",
  "eventName": "ticket.compensation-executed.v1",
  "ticketId": "TCK-1001",
  "recoveryId": "recovery-040",
  "sourceReference": "event-or-case-id",
  "decision": "APPLIED",
  "occurredAt": "2026-08-09T12:00:00Z"
}
```

## Rules

- eventId is idempotent.
- Payload is redacted.
- correction/replay/compensation references sourceReference.
