# SPEC-TW-032 API Contract

## Endpoint

`POST /v1/tickets/{ticketId}/escalation/resume`

## Request

```json
{
  "idempotencyKey": "idem-phase8-032",
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
  "state": "IN_PROGRESS",
  "workflowVersion": 43,
  "auditId": "audit-032",
  "eventName": "ticket.escalation-resumed.v1"
}
```

## Errors

- `400 BAD_REQUEST`: missing field or invalid reason.
- `403 FORBIDDEN`: actor is not authorized.
- `404 NOT_FOUND`: Ticket does not exist.
- `409 CONFLICT`: state, version, or workflow cycle mismatch.
