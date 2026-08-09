# SPEC-TW-039 Event Contract

## Published / Recorded Event

`ticket.correction-event-published.v1`

```json
{
  "eventId": "evt-039",
  "eventName": "ticket.correction-event-published.v1",
  "ticketId": "TCK-1001",
  "recoveryId": "recovery-039",
  "sourceReference": "event-or-case-id",
  "decision": "APPLIED",
  "occurredAt": "2026-08-09T12:00:00Z"
}
```

## 规则

- eventId 幂等；
- payload 脱敏；
- correction/replay/compensation 必须引用 sourceReference。
