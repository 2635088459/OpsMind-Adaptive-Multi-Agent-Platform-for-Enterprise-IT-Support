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

- `400 BAD_REQUEST`: invalid input.
- `403 FORBIDDEN`: actor lacks recovery permission.
- `404 NOT_FOUND`: target case/event/ticket does not exist.
- `409 CONFLICT`: state, version, attempt, or source reference conflict.
