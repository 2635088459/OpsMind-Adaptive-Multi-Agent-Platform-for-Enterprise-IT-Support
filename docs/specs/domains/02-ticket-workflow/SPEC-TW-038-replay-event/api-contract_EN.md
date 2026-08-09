# SPEC-TW-038 API Contract

## Endpoint

`POST /internal/v1/tickets/events/replay`

## Request

```json
{
  "idempotencyKey": "idem-phase10-038",
  "actorId": "ops-operator",
  "reasonCode": "RECOVERY_REQUIRED",
  "reason": "Controlled recovery action for a verified inconsistency",
  "correlationId": "corr-038",
  "sourceReference": "event-or-case-id"
}
```

## Response 200

```json
{
  "decision": "APPLIED",
  "recoveryId": "recovery-038",
  "eventName": "ticket.event-replay-recorded.v1"
}
```

## Errors

- `400 BAD_REQUEST`: invalid input.
- `403 FORBIDDEN`: actor lacks recovery permission.
- `404 NOT_FOUND`: target case/event/ticket does not exist.
- `409 CONFLICT`: state, version, attempt, or source reference conflict.
