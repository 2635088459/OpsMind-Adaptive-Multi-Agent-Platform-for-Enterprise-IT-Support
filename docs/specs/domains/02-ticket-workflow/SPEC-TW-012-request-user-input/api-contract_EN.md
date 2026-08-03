# SPEC-TW-012 — API Contract

## Endpoint

```http
POST /api/v1/tickets/{ticketId}/user-input-requests
```

Headers:

```text
Authorization: Bearer <token>
If-Match: "<ticket-version>"
Idempotency-Key: <stable-key>
X-Correlation-ID: <uuid>
```

Request:

```json
{
  "prompt": "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.",
  "requestedFields": ["screenshot", "vpnStatus"],
  "expiresAt": "2026-08-05T18:00:00Z"
}
```

Response:

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "requestId": "3d912886-9652-4d88-8a64-1297b50f14c7",
  "previousStatus": "IN_PROGRESS",
  "status": "WAITING_FOR_USER",
  "prompt": "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.",
  "requestedBy": "sam.support",
  "requestedAt": "2026-08-03T18:00:00Z",
  "waitingForRequesterSince": "2026-08-03T18:00:00Z",
  "version": 21
}
```

Success returns `201 Created` with `ETag` and `Location`.

Errors: `400 VALIDATION_ERROR`, `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 TICKET_NOT_FOUND`, `409 INVALID_STATUS_TRANSITION`, `409 USER_INPUT_REQUEST_ALREADY_OPEN`, `412 VERSION_CONFLICT`, `428 PRECONDITION_REQUIRED`.
