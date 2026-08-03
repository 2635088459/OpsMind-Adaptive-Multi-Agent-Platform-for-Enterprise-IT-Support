# SPEC-TW-011 — API Contract

## 1. Close Endpoint

```http
POST /api/v1/tickets/{ticketId}/closure
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
  "closeReasonCode": "REQUESTER_CONFIRMED",
  "closeReason": "Requester confirmed the issue is resolved and no further action is required."
}
```

Response:

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "previousStatus": "RESOLVED",
  "status": "CLOSED",
  "closeReasonCode": "REQUESTER_CONFIRMED",
  "closedBy": "sam.support",
  "closedAt": "2026-07-31T20:10:00Z",
  "resolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "version": 19
}
```

## 2. Reopen Endpoint

```http
POST /api/v1/tickets/{ticketId}/reopen
```

Request:

```json
{
  "reopenReasonCode": "ISSUE_RECURRED",
  "reopenReason": "The requester reported the same endpoint enrollment failure returned after reboot."
}
```

Response:

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "previousStatus": "CLOSED",
  "status": "IN_PROGRESS",
  "previousResolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "newResolutionCycleId": "b2b0eb44-aecf-4e4d-a77a-2b09d9eab2e8",
  "reopenReasonCode": "ISSUE_RECURRED",
  "reopenedBy": "sam.support",
  "reopenedAt": "2026-07-31T21:30:00Z",
  "reopenCount": 1,
  "ownershipStatus": "ACTIVE",
  "version": 20
}
```

## 3. Close Reason Codes

```text
REQUESTER_CONFIRMED
SUPPORT_CONFIRMED
AUTO_CLOSE_TIMEOUT
NO_FURTHER_ACTION_REQUIRED
DUPLICATE_CLOSED
```

`AUTO_CLOSE_TIMEOUT` is reserved for a later scheduler; this SPEC does not implement auto-close jobs.

## 4. Reopen Reason Codes

```text
ISSUE_RECURRED
RESOLUTION_FAILED
REQUESTER_REPORTED_NOT_FIXED
SUPPORT_REVIEW_REQUIRED
RELATED_FAILURE_DISCOVERED
```

## 5. HTTP Status

- `200 OK`: close/reopen succeeded;
- `400 VALIDATION_ERROR`: payload or header format error;
- `401 UNAUTHENTICATED`;
- `403 FORBIDDEN` / `QUEUE_ACCESS_DENIED`;
- `404 TICKET_NOT_FOUND`;
- `409 INVALID_STATUS_TRANSITION` / `IDEMPOTENCY_KEY_REUSED`;
- `412 VERSION_CONFLICT`;
- `422 REOPEN_WINDOW_EXPIRED` or another business-rule failure;
- `428 PRECONDITION_REQUIRED`.

## 6. Response Headers

Successful responses include:

```text
ETag: "<new-version>"
Location: /api/v1/tickets/{ticketId}
```
