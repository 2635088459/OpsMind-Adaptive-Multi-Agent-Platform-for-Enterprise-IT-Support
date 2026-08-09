# SPEC-TW-037 Event Contract

## Published / Recorded Event

`ticket.reconciliation-case-opened.v1`

```json
{
  "eventId": "evt-037",
  "eventName": "ticket.reconciliation-case-opened.v1",
  "ticketId": "TCK-1001",
  "recoveryId": "recovery-037",
  "sourceReference": "event-or-case-id",
  "decision": "APPLIED",
  "occurredAt": "2026-08-09T12:00:00Z"
}
```

## 规则

- eventId 幂等；
- payload 脱敏；
- correction/replay/compensation 必须引用 sourceReference。
