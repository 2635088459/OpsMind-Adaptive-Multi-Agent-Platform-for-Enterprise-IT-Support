# SPEC-TW-003 — List Requester Tickets

> **Spec ID:** SPEC-TW-003  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Actor:** EMPLOYEE  
> **API:** `GET /api/v1/tickets`  
> **Dependencies:** SPEC-TW-001 and SPEC-TW-002  
> **Status:** Proposed for Review

---

# 1. Purpose

Defines the complete behavior for an authenticated Employee listing Tickets they created.

```text
Authenticated Employee
→ tickets:read:self
→ requester_id = principal.subject
→ approved filters
→ stable keyset pagination
→ bounded Ticket summaries
```

The system guarantees:

- Employees see only their own Tickets.
- Ownership is enforced in SQL.
- Page size is bounded.
- Cursors are tamper-resistant and bound to filters, sorting, and actor scope.
- New Ticket insertion does not duplicate older rows on later pages.
- The query never mutates Ticket state.
- Responses exclude full Description and internal fields.

---

# 2. Scope

Included:

- JWT authentication
- `tickets:read:self`
- Requester ownership
- `status` filter
- `applicationCode` filter
- `createdFrom` and `createdTo`
- Stable sorting
- Cursor pagination
- Empty-list behavior
- Response schemas
- Error contract
- Query telemetry
- PostgreSQL query-plan verification

Excluded:

- Support Queue
- Ticket detail
- Messages
- Timeline
- Search
- Offset pagination
- Total count
- Ticket mutation

---

# 3. HTTP Contract

```http
GET /api/v1/tickets
Authorization: Bearer <JWT>
Accept: application/json
```

Supported query parameters:

```text
limit
cursor
status
applicationCode
createdFrom
createdTo
```

Response headers:

```http
Cache-Control: private, no-store
Vary: Authorization
Content-Type: application/json
```

---

# 4. Authentication and Ownership

JWT validation includes signature, issuer, audience, expiration, subject, token type, and environment.

Missing or invalid JWT:

```text
401 UNAUTHENTICATED
```

Required scope:

```text
tickets:read:self
```

Missing scope:

```text
403 FORBIDDEN
```

SQL must include:

```sql
WHERE requester_id = :principalSubject
```

The implementation must not read another user's rows and filter them later in Java.

---

# 5. Filter Contract

## `status`

Accepts one or more TicketStatus values, with a maximum of 10. Invalid values return `400 VALIDATION_ERROR`.

## `applicationCode`

Allowed values:

```text
HOUSING_PORTAL
EMAIL
VPN
OTHER
```

## `createdFrom`

```text
created_at >= createdFrom
```

## `createdTo`

```text
created_at < createdTo
```

Require:

```text
createdFrom < createdTo
```

The recommended maximum range is 365 days.

Before creating the filter fingerprint:

- Sort and deduplicate enum values.
- Normalize date-times to UTC.
- Exclude `limit` and `cursor`.

---

# 6. Page Size

```text
default = 20
minimum = 1
maximum = 50
```

Out-of-range values return `400 VALIDATION_ERROR`.

---

# 7. Sorting

The MVP uses fixed sorting:

```text
createdAt DESC
ticketId DESC
```

SQL:

```sql
ORDER BY created_at DESC, ticket_id DESC
```

TicketId is the unique tie-breaker. Client-defined sorting is not supported in the MVP.

---

# 8. Cursor Pagination

Uses keyset pagination.

The first page has no cursor.

A next-page cursor contains at least:

```json
{
  "version": 1,
  "lastCreatedAt": "2026-07-22T18:00:00Z",
  "lastTicketId": "018f0e00-1111-7111-8111-111111111111",
  "filterFingerprint": "sha256:...",
  "sort": "createdAt:desc,ticketId:desc",
  "issuedAt": "2026-07-25T18:00:00Z",
  "expiresAt": "2026-07-26T18:00:00Z"
}
```

Recommended format:

```text
base64url(payload) + "." + base64url(HMAC-SHA-256(payload))
```

The cursor must be:

- Opaque
- Tamper-resistant
- Versioned
- Expiring after 24 hours
- Bound to filters
- Bound to operation
- Bound to actor scope
- Excluded from normal logs
- Never used as a metric label

Next-page predicate:

```sql
AND (
  created_at < :lastCreatedAt
  OR (
    created_at = :lastCreatedAt
    AND ticket_id < :lastTicketId
  )
)
```

Read `limit + 1` rows to determine `hasMore`.

Return `400 INVALID_CURSOR` for malformed, modified, expired, mismatched-filter, mismatched-sort, mismatched-actor, or mismatched-operation cursors.

---

# 9. Stable Pagination

The cursor uses immutable sort keys:

```text
createdAt
ticketId
```

Therefore:

- Newly created Tickets do not appear in later pages of an older cursor.
- Previously returned Tickets do not repeat.
- Equal creation times are resolved by TicketId.

Mutable fields such as `updatedAt`, `status`, and `priority` are not cursor sort keys.

---

# 10. Response Contract

```json
{
  "items": [
    {
      "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
      "displayId": "INC-2048",
      "title": "Cannot sign in to Housing Portal",
      "applicationCode": "HOUSING_PORTAL",
      "status": "NEW",
      "priority": "UNASSIGNED",
      "createdAt": "2026-07-23T16:30:00Z",
      "updatedAt": "2026-07-23T16:30:00Z",
      "version": 0
    }
  ],
  "page": {
    "limit": 20,
    "hasMore": true,
    "nextCursor": "opaque-signed-cursor"
  },
  "appliedFilters": {
    "status": [],
    "applicationCode": [],
    "createdFrom": null,
    "createdTo": null
  }
}
```

Allowed summary fields:

- TicketId
- DisplayId
- Title
- ApplicationCode
- Status
- Priority
- CreatedAt
- UpdatedAt
- Version

Forbidden:

- Description
- RequesterId
- Requester email
- Internal assignment
- Internal notes
- Workflow ID
- Approval, tool, reconciliation, or audit metadata
- Secrets

---

# 11. Empty List

When no Tickets exist:

```text
HTTP 200
items = []
hasMore = false
nextCursor = null
```

Do not return `404`.

---

# 12. Query Architecture

```text
PublicTicketQueryController
→ ListRequesterTicketsUseCase
→ ListRequesterTicketsApplicationService
→ RequesterTicketQueryPort
→ JdbcRequesterTicketQueryAdapter
→ PostgreSQL
```

Rules:

- Use JDBC projections.
- Use parameterized SQL.
- Enforce ownership in SQL.
- Do not rehydrate the aggregate.
- Do not read Description, Messages, or Timeline.
- Do not write to the database.
- Do not call remote services.
- No N+1.
- No count query.
- No offset scan.

---

# 13. SQL Shape

```sql
SELECT
  ticket_id,
  display_id,
  title,
  application_code,
  status,
  priority,
  created_at,
  updated_at,
  version
FROM ticket.tickets
WHERE requester_id = :requesterId
  AND (:statusesEmpty OR status = ANY(:statuses))
  AND (:applicationsEmpty OR application_code = ANY(:applicationCodes))
  AND (:createdFrom IS NULL OR created_at >= :createdFrom)
  AND (:createdTo IS NULL OR created_at < :createdTo)
  AND (
    :cursorAbsent
    OR created_at < :lastCreatedAt
    OR (
      created_at = :lastCreatedAt
      AND ticket_id < :lastTicketId
    )
  )
ORDER BY created_at DESC, ticket_id DESC
LIMIT :limitPlusOne
```

---

# 14. Index Strategy

At minimum evaluate:

```sql
(requester_id, created_at DESC, ticket_id DESC)
```

Potential filter indexes:

```text
(requester_id, status, created_at DESC, ticket_id DESC)
(requester_id, application_code, created_at DESC, ticket_id DESC)
```

Indexes require evidence from query plans and `EXPLAIN (ANALYZE, BUFFERS)`.

---

# 15. Errors

| Scenario | HTTP | Code |
|---|---:|---|
| Invalid limit, filter, or date | 400 | `VALIDATION_ERROR` |
| Invalid or expired cursor | 400 | `INVALID_CURSOR` |
| Missing or invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing scope | 403 | `FORBIDDEN` |
| Rate limited | 429 | `RATE_LIMITED` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Cursor errors never expose the internal payload or cryptographic failure details.

---

# 16. Audit and Security Logging

Normal Employee self-list does not create a Business Audit row by default.

Security Audit may be recorded for:

- Missing scope
- Cursor tampering
- Repeated invalid cursors
- Abuse and rate limits
- Cross-environment tokens

Audit and logs never store the raw cursor.

---

# 17. Observability

Recommended traces:

```text
HTTP GET /api/v1/tickets
ListRequesterTicketsUseCase
ticket.cursor.decode
ticket.cursor.validate
db.ticket.requester_list
ticket.cursor.encode
```

Metrics:

```text
opsmind_ticket_list_total
opsmind_ticket_list_duration_seconds
opsmind_ticket_list_result_count
opsmind_ticket_list_invalid_cursor_total
opsmind_ticket_list_authorization_denied_total
```

Allowed low-cardinality labels:

```text
result
status_class
cursor_present
has_filters
```

Never include RequesterId, TicketId, raw cursor, title, or JWT.

---

# 18. Performance

Targets:

```text
p95 < 300 ms
p99 < 1 s
```

Requirements:

- Maximum page size 50
- One main query
- No count query
- No N+1
- No offset scan
- Do not read Description
- Use requester and sort indexes

---

# 19. Tests First

```text
ListRequesterTicketsApplicationServiceTest
ListRequesterTicketsFilterValidationTest
ListRequesterTicketsPageSizeTest
TicketListCursorCodecTest
TicketListCursorSignatureTest
TicketListCursorExpiryTest
TicketListCursorFilterBindingTest
TicketListCursorPrincipalBindingTest
ListRequesterTicketsControllerTest
ListRequesterTicketsOwnershipIT
ListRequesterTicketsCrossUserIsolationIT
ListRequesterTicketsProjectionIT
ListRequesterTicketsCursorIT
ListRequesterTicketsStableSortIT
ListRequesterTicketsConcurrentInsertIT
ListRequesterTicketsFilterIT
ListRequesterTicketsQueryPlanIT
ListRequesterTicketsResponseRedactionTest
ListRequesterTicketsDoesNotMutateIT
ListRequesterTicketsDoesNotCreateOutboxIT
```

---

# 20. Package Mapping

```text
ticket.api.publicapi
├── PublicTicketQueryController
├── RequesterTicketListResponse
├── RequesterTicketSummaryResponse
└── RequesterTicketListApiMapper

ticket.application.port.in
└── ListRequesterTicketsUseCase

ticket.application.query
├── ListRequesterTicketsQuery
├── RequesterTicketListResult
├── RequesterTicketSummary
├── TicketListFilters
└── TicketListCursor

ticket.application.service
└── ListRequesterTicketsApplicationService

ticket.application.port.out
└── RequesterTicketQueryPort

ticket.infrastructure.query
├── JdbcRequesterTicketQueryAdapter
├── TicketListCursorCodec
├── TicketListCursorSigner
└── RequesterTicketQuerySql
```

---

# 21. Definition of Done

- [ ] Employees see only their own Tickets.
- [ ] Ownership is enforced in SQL.
- [ ] Page size is 1–50.
- [ ] Sorting is `createdAt DESC, ticketId DESC`.
- [ ] Cursor uses keyset pagination.
- [ ] Cursor is signed, versioned, and expiring.
- [ ] Cursor binds filters and actor scope.
- [ ] Empty list returns 200.
- [ ] Stable-pagination tests pass.
- [ ] Concurrent-insert tests pass.
- [ ] Response excludes Description and internal fields.
- [ ] Query does not mutate Tickets.
- [ ] Query emits no Outbox event.
- [ ] Query plan uses expected indexes.
- [ ] No offset, count query, or N+1.
- [ ] JSON Schema and telemetry-redaction tests pass.
- [ ] ArchUnit and `./mvnw clean verify` pass.
- [ ] CI and traceability updates are complete.

---

# 22. Business Guarantee

```text
Employees can quickly and consistently browse their own Tickets
without seeing another user's or internal data.
Filters, sorting, and cursors are deterministic, bounded, and tamper-resistant,
and newly created Tickets do not corrupt later-page results.
```
