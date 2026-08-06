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

- `400 BAD_REQUEST`: missing field or invalid reason.
- `403 FORBIDDEN`: actor is not authorized.
- `404 NOT_FOUND`: Ticket does not exist.
- `409 CONFLICT`: state, version, or workflow cycle mismatch.
