# SPEC-TW-030 API Contract

## Endpoint

`POST /v1/tickets/{ticketId}/assignment`

## Request

```json
{
  "idempotencyKey": "idem-phase8-030",
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
  "state": "same lifecycle state",
  "workflowVersion": 43,
  "auditId": "audit-030",
  "eventName": "ticket.assigned.v1"
}
```

## Errors

- `400 BAD_REQUEST`: missing field or invalid reason.
- `403 FORBIDDEN`: actor is not authorized.
- `404 NOT_FOUND`: Ticket does not exist.
- `409 CONFLICT`: state, version, or workflow cycle mismatch.
