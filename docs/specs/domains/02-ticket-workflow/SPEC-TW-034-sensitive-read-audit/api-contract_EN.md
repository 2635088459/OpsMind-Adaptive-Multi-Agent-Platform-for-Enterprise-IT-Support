# SPEC-TW-034 API Contract

## Internal Policy Endpoint

`POST /internal/v1/audit/sensitive-read-policy/evaluate`

This endpoint represents the policy contract for this SPEC. Real business endpoints may call the same application policy inline without exposing this internal endpoint.

## Request

```json
{
  "ticketId": "TCK-1001",
  "actorId": "user-123",
  "actorType": "IT_SUPPORT",
  "operation": "ticket.command",
  "context": {
    "supportQueueId": "00000000-0000-0000-0000-000000000001"
  }
}
```

## Response 200

```json
{
  "decision": "ALLOW",
  "decisionCode": "POLICY_ALLOWED",
  "auditRequired": true
}
```

## Errors

- `400 BAD_REQUEST`: invalid policy input.
- `403 FORBIDDEN`: policy denied.
- `409 CONFLICT`: current Ticket state/context is not allowed.
- `500 INTERNAL_ERROR`: required audit/secret/step-up guard failed closed.
