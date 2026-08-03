# SPEC-TW-010 — API Contract

## 1. Common Headers

```http
Authorization: Bearer <token>
Content-Type: application/json
If-Match: "<positive-version>"
Idempotency-Key: <8-to-128-character-key>
X-Correlation-ID: <optional-UUID>
```

The server derives actor identity and authorized queues from authentication. Clients must not provide actor, tenant, queue scope, resolvedBy, or resolvedAt in the body.

## 2. Resolve Ticket

```http
POST /api/v1/tickets/{ticketId}/resolution
```

```json
{
  "resolutionCode": "FIXED",
  "resolutionSummary": "Reinstalled the endpoint management profile and confirmed the device checked in successfully."
}
```

Requires current status `IN_PROGRESS` and an existing assignee.

## 3. Success Response

```http
HTTP/1.1 200 OK
ETag: "18"
Location: /api/v1/tickets/6c2ad02e-c394-41fb-8e38-dfffd581a59d
```

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "previousStatus": "IN_PROGRESS",
  "status": "RESOLVED",
  "resolutionCode": "FIXED",
  "resolutionSummary": "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
  "resolvedBy": "sam.support",
  "resolvedAt": "2026-07-31T19:05:00Z",
  "version": 18
}
```

## 4. Error Shape

```json
{
  "type": "https://api.opsmind.example/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "code": "INVALID_STATUS_TRANSITION",
  "detail": "Only an IN_PROGRESS ticket can be resolved.",
  "instance": "/api/v1/tickets/{ticketId}/resolution",
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec"
}
```

## 5. Status Mapping

| Condition | HTTP |
|---|---|
| validation or malformed header | `400` |
| missing/invalid authentication | `401` |
| actor or queue denied | `403` |
| ticket not found | `404` |
| invalid transition, missing assignee, cycle conflict, idempotency conflict | `409` |
| unsupported resolution code | `422` |
| stale version | `412` |
| missing `If-Match` | `428` |
| unsupported media type | `415` |

## 6. Validation

- `resolutionCode` must be from the controlled enum.
- Trimmed `resolutionSummary` length is 10 to 5000 characters.
- Clients must not submit previous status, status, actor, assignee, resolvedAt, or version body fields.
- `If-Match` contains exactly one strong positive integer ETag.
- Idempotency fingerprint includes actor, operation, ticket ID, normalized body, and expected version.
