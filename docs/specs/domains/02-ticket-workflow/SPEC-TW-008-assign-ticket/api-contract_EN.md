# SPEC-TW-008 — API Contract

## 1. Common Headers

```http
Authorization: Bearer <token>
Content-Type: application/json
If-Match: "<positive-version>"
Idempotency-Key: <8-to-128-character-key>
X-Correlation-ID: <optional-UUID>
```

The server derives `tenantId` and `actorId` from authentication. Clients must not provide them in the body.

## 2. Assign

```http
POST /api/v1/tickets/{ticketId}/assign
```

```json
{
  "assigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "reason": "Primary endpoint support owner"
}
```

Requires a `TRIAGED` ticket with no assignee.

## 3. Reassign

```http
POST /api/v1/tickets/{ticketId}/reassign
```

```json
{
  "assigneeId": "98bf86d3-d709-448b-acd9-ef9ecbbc3d23",
  "reason": "Escalated to network specialist"
}
```

The new assignee must differ from the current assignee. Status is preserved.

## 4. Unassign

```http
POST /api/v1/tickets/{ticketId}/unassign
```

```json
{
  "reason": "Agent left the support rotation"
}
```

Requires status `ASSIGNED`.

## 5. Success Response

```http
HTTP/1.1 200 OK
ETag: "13"
Location: /api/v1/tickets/6c2ad02e-c394-41fb-8e38-dfffd581a59d
```

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "status": "ASSIGNED",
  "assignee": {
    "id": "17cb78fb-c36d-4bb2-9687-84d86d726192",
    "displayName": "Sam Lee"
  },
  "assignedAt": "2026-07-29T19:15:00Z",
  "version": 13
}
```

For unassign, `assignee` and `assignedAt` are `null`.

## 6. Error Shape

```json
{
  "type": "https://api.opsmind.example/problems/version-conflict",
  "title": "Version conflict",
  "status": 409,
  "code": "VERSION_CONFLICT",
  "detail": "The ticket was modified by another command.",
  "instance": "/api/v1/tickets/{ticketId}/assign",
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec"
}
```

## 7. Status Mapping

| Condition | HTTP |
|---|---|
| validation or malformed header | `400` |
| missing/invalid authentication | `401` |
| role or queue denied | `403` |
| ticket or assignee not found | `404` |
| state, version, eligibility, or idempotency conflict | `409` |
| unsupported media type | `415` |

## 8. Validation

- IDs must be UUIDs.
- `reason` is required after trimming and has 3–500 characters.
- `assigneeId` is required for assign/reassign and forbidden for unassign.
- `If-Match` contains exactly one strong positive integer ETag.
- Idempotency fingerprint includes tenant, actor, operation, ticket ID, normalized body, and expected version.
