# SPEC-TW-029 API Contract

## Endpoint

`POST /v1/tickets/{ticketId}/cancel`

## Request

```json
{
  "idempotencyKey": "idem-phase8-029",
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
  "state": "CANCELLED",
  "workflowVersion": 43,
  "auditId": "audit-029",
  "eventName": "ticket.cancelled.v1"
}
```

## Errors

- `400 BAD_REQUEST`：字段缺失或 reason 无效；
- `403 FORBIDDEN`：actor 无权限；
- `404 NOT_FOUND`：Ticket 不存在；
- `409 CONFLICT`：状态、version 或 workflow cycle 不匹配。
