# SPEC-TW-041 API Contract

## Endpoint

`POST /internal/v1/tickets/integrity-repairs`

## Request

```json
{
  "idempotencyKey": "idem-phase10-041",
  "actorId": "ops-operator",
  "reasonCode": "RECOVERY_REQUIRED",
  "reason": "Controlled recovery action for a verified inconsistency",
  "correlationId": "corr-041",
  "sourceReference": "event-or-case-id"
}
```

## Response 200

```json
{
  "decision": "APPLIED",
  "recoveryId": "recovery-041",
  "eventName": "ticket.integrity-repair-applied.v1"
}
```

## Errors

- `400 BAD_REQUEST`：输入无效；
- `403 FORBIDDEN`：actor 无恢复权限；
- `404 NOT_FOUND`：目标 case/event/ticket 不存在；
- `409 CONFLICT`：状态、version、attempt 或 source reference 冲突。
