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

## 规则

- eventId 幂等；
- payload 脱敏；
- correction/replay/compensation 必须引用 sourceReference。
