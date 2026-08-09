# SPEC-TW-040 API Contract

## Endpoint

`POST /internal/v1/tickets/{ticketId}/compensations`

## Request

```json
{
  "idempotencyKey": "idem-phase10-040",
  "actorId": "ops-operator",
  "reasonCode": "RECOVERY_REQUIRED",
  "reason": "Controlled recovery action for a verified inconsistency",
  "correlationId": "corr-040",
  "sourceReference": "event-or-case-id"
}
```

## Response 200

```json
{
  "decision": "APPLIED",
  "recoveryId": "recovery-040",
  "eventName": "ticket.compensation-executed.v1"
}
```

## Errors

- `400 BAD_REQUEST`：输入无效；
- `403 FORBIDDEN`：actor 无恢复权限；
- `404 NOT_FOUND`：目标 case/event/ticket 不存在；
- `409 CONFLICT`：状态、version、attempt 或 source reference 冲突。
