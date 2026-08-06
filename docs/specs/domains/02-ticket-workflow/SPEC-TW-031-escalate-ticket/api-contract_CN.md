# SPEC-TW-031 API Contract

## Endpoint

`POST /v1/tickets/{ticketId}/escalation`

## Request

```json
{
  "idempotencyKey": "idem-phase8-031",
  "expectedVersion": 42,
  "actorId": "user-123",
  "reasonCode": "REQUESTED",
  "reason": "Phase 8 lifecycle command reason"
}
```

## Response 200

```json
{
  "ticketId": "TCK-1001",
  "state": "ESCALATED",
  "workflowVersion": 43,
  "auditId": "audit-031",
  "eventName": "ticket.escalated.v1"
}
```

## Errors

- `400 BAD_REQUEST`：字段缺失或 reason 无效；
- `403 FORBIDDEN`：actor 无权限；
- `404 NOT_FOUND`：Ticket 不存在；
- `409 CONFLICT`：状态、version 或 workflow cycle 不匹配。
