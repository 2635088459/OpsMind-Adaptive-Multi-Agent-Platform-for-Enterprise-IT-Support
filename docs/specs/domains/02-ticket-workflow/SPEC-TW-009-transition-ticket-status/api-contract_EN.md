# SPEC-TW-009 — API Contract

## 1. Common Headers

```http
Authorization: Bearer <token>
Content-Type: application/json
If-Match: "<positive-version>"
Idempotency-Key: <8-to-128-character-key>
X-Correlation-ID: <optional-UUID>
```

The server derives actor identity and authorized queues from authentication. Clients must not provide actor, tenant, or queue scope in the body.

## 2. Transition Status

```http
POST /api/v1/tickets/{ticketId}/status-transitions
```

### 2.1 Start Work

```json
{
  "targetStatus": "IN_PROGRESS",
  "reason": "Starting endpoint investigation"
}
```

Requires current status `ASSIGNED` and an existing assignee.

### 2.2 Wait for User

```json
{
  "targetStatus": "WAITING_FOR_USER",
  "reason": "Requester must provide device serial number",
  "waitingForRequesterSince": "2026-07-31T18:35:00Z"
}
```

`waitingForRequesterSince` is optional. If omitted, the server uses command time.

### 2.3 Wait for Approval

```json
{
  "targetStatus": "WAITING_FOR_APPROVAL",
  "reason": "Privileged remediation requires manager approval",
  "approvalReference": "approval-req-20260731-018"
}
```

`approvalReference` is required and has 3 to 128 characters.

### 2.4 Resume Work

```json
{
  "targetStatus": "IN_PROGRESS",
  "reason": "Requester provided the missing device details"
}
```

Requires current status `WAITING_FOR_USER` or `WAITING_FOR_APPROVAL`.

## 3. Success Response

```http
HTTP/1.1 200 OK
ETag: "14"
Location: /api/v1/tickets/6c2ad02e-c394-41fb-8e38-dfffd581a59d
```

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "previousStatus": "ASSIGNED",
  "status": "IN_PROGRESS",
  "reason": "Starting endpoint investigation",
  "waitingForRequesterSince": null,
  "approvalReference": null,
  "transitionedAt": "2026-07-31T18:35:00Z",
  "version": 14
}
```

## 4. Error Shape

```json
{
  "type": "https://api.opsmind.example/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "code": "INVALID_STATUS_TRANSITION",
  "detail": "The requested ticket status transition is not allowed.",
  "instance": "/api/v1/tickets/{ticketId}/status-transitions",
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
| invalid transition, missing assignee, idempotency conflict | `409` |
| stale version | `412` |
| missing `If-Match` | `428` |
| unsupported media type | `415` |

## 6. Validation

- `targetStatus` must be `IN_PROGRESS`, `WAITING_FOR_USER`, or `WAITING_FOR_APPROVAL`.
- Trimmed `reason` length is 3 to 500 characters.
- `approvalReference` is allowed and required only for `WAITING_FOR_APPROVAL`.
- `waitingForRequesterSince` is allowed only for `WAITING_FOR_USER`.
- Clients must not submit previous status, actor, assignee, or version body fields.
- `If-Match` contains exactly one strong positive integer ETag.
- Idempotency fingerprint includes actor, operation, ticket ID, normalized body, and expected version.
