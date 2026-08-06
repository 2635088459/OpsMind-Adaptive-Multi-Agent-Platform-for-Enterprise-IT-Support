# SPEC-TW-026 API Contract

## Endpoint

`POST /v1/tickets/{ticketId}/resolution-confirmation`

## Request

```json
{
  "idempotencyKey": "idem-phase8-026",
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
  "state": "CLOSED",
  "workflowVersion": 43,
  "auditId": "audit-026",
  "eventName": "ticket.resolution-confirmed.v1"
}
```

## Errors

- `400 BAD_REQUEST`: missing field or invalid reason.
- `403 FORBIDDEN`: actor is not authorized.
- `404 NOT_FOUND`: Ticket does not exist.
- `409 CONFLICT`: state, version, or workflow cycle mismatch.
