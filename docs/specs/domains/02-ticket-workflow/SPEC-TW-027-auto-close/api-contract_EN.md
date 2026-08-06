# SPEC-TW-027 API Contract

## Endpoint

`POST /internal/v1/tickets/{ticketId}/auto-close`

## Request

```json
{
  "idempotencyKey": "idem-phase8-027",
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
  "auditId": "audit-027",
  "eventName": "ticket.auto-closed.v1"
}
```

## Errors

- `400 BAD_REQUEST`: missing field or invalid reason.
- `403 FORBIDDEN`: actor is not authorized.
- `404 NOT_FOUND`: Ticket does not exist.
- `409 CONFLICT`: state, version, or workflow cycle mismatch.
