# SPEC-TW-006 — Ticket Timeline

> **Spec ID:** SPEC-TW-006  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Actors:** EMPLOYEE, IT_SUPPORT, IT_ADMIN, IT_MANAGER, AUDITOR  
> **API:** `GET /api/v1/tickets/{ticketId}/timeline`  
> **Dependencies:** SPEC-TW-001, SPEC-TW-002, SPEC-TW-004, and SPEC-TW-005  
> **Business Events:** None; a pure query emits no Outbox event

---

# 1. Purpose

This specification defines the complete behavior for an authorized actor viewing a unified Timeline for one Ticket.

```text
Authenticated Actor
→ Coarse Scope Check
→ Ticket Resource Authorization
→ Server-selected Timeline View
→ Fixed Snapshot Boundary
→ Unified PostgreSQL Projection
→ Visibility Filtering
→ Stable Keyset Pagination
→ Optional Sensitive-read Audit
→ HTTP 200
```

Phase 02 Timeline sources are:

```text
Ticket Created
Ticket Status History
Public Requester Messages
Public Support Messages
Internal Support Notes
```

The system guarantees:

- Employees view only public Timeline items from owned Tickets.
- Support views only Tickets within authorized resource scope.
- Internal notes are visible only to approved Support or Auditor views.
- The server selects the view from the trusted principal and policy.
- Clients cannot select a higher-privilege view through headers or query parameters.
- A fixed `snapshotAt` keeps one cursor session stable.
- Ordering is deterministic and has a unique tie-breaker.
- Queries never mutate Tickets, messages, history, or business state.
- Queries emit no Status History, Domain Event, or Outbox event.
- Employee responses exclude internal actors, reasons, notes, and Audit metadata.
- Phase 02 does not expose raw Audit records as Timeline items.

---

# 2. Scope

Included:

- Employee public Timeline
- Support public and internal Timeline views
- Auditor policy hook
- JWT authentication
- Resource authorization
- Actor-specific projection
- Snapshot boundary
- Keyset cursor pagination
- Stable Timeline ordering
- Ticket-created items
- Status-changed items
- Public requester messages
- Public support messages
- Internal support notes
- Required sensitive-read Audit
- Error contract
- Observability
- PostgreSQL projection and query-plan tests
- JSON Schema
- Automated tests

Excluded:

- Timeline mutations
- Raw Audit Log API
- Approval details
- Tool execution details
- Verification evidence
- Reconciliation detail
- Attachment download
- Timeline search or export
- WebSocket live updates
- Full-text or semantic search
- Arbitrary sorting
- Offset pagination
- Total count
- Cross-service Timeline aggregation

New item types in later phases require schema, rank, visibility, cursor-version, contract-test, and traceability updates.

---

# 3. HTTP Contract

```http
GET /api/v1/tickets/{ticketId}/timeline
Authorization: Bearer <JWT>
Accept: application/json
```

Supported query parameters:

```text
limit
cursor
```

Response headers:

```http
Cache-Control: private, no-store
Pragma: no-cache
Vary: Authorization
Content-Type: application/json
```

The MVP does not use Ticket version as a Timeline ETag because messages do not update Ticket version. Conditional GET is therefore excluded from this Spec.

---

# 4. Path Parameter

`ticketId` must be a canonical UUID. Invalid values return `400 VALIDATION_ERROR`.

Display ID lookup is outside this API.

---

# 5. Authentication and Coarse Scopes

JWT validation includes signature, issuer, audience, expiration, not-before, subject, authorized party, token type, and environment.

Invalid JWT returns `401 UNAUTHENTICATED`.

Employees require `tickets:read:self`.

Support requires `tickets:read:queue`.

Internal notes additionally require `tickets:timeline:internal`, or the approved equivalent.

Auditors require `tickets:audit:timeline`.

Missing coarse scope returns `403 FORBIDDEN`.

---

# 6. Resource Authorization

Employee authorization requires:

```sql
ticket_id = :ticketId
AND requester_id = :principalSubject
```

Cross-user access returns `404 TICKET_NOT_FOUND`.

Support authorization includes application, team, queue, tenant, region, classification, and assignment scope from trusted security context or a local projection.

The synchronous query never calls a remote policy service.

Missing or out-of-scope resources return the same safe `404`.

---

# 7. View Resolution

Clients cannot submit a view, internal flag, role, or scope.

The server resolves:

```text
EMPLOYEE
→ EMPLOYEE_PUBLIC_VIEW

Support with resource access and internal scope
→ SUPPORT_INTERNAL_VIEW

Support with resource access but no internal scope
→ SUPPORT_PUBLIC_VIEW

AUDITOR
→ AUDITOR_POLICY_VIEW
```

Multiple roles are resolved by explicit policy, not by automatically choosing the broadest response.

---

# 8. Timeline Sources

## Ticket Created

Mapped from `ticket.tickets` as a public `TICKET_CREATED` item at Ticket creation time.

## Status History

Mapped from `ticket.ticket_status_history` as public `STATUS_CHANGED` items.

Employees receive public status transitions but not internal reason codes, source events, workflow IDs, or actor IDs.

Support may receive approved transition IDs, reason codes, and pseudonymous actor references.

## Public Requester Message

Mapped from public requester message rows and visible to Employee and Support views.

## Public Support Message

Mapped from public support message rows and visible to Employee and Support views.

## Internal Support Note

Mapped only for approved internal Support or Auditor views.

---

# 9. Raw Audit Records Are Not Timeline Sources

Phase 02 never exposes `ticket.audit_records` directly.

Audit records serve compliance and security purposes and may contain internal metadata.

A future audit-safe business-action projection requires a dedicated specification.

---

# 10. Timeline Item Identity

Without a dedicated Timeline table, stable IDs are:

```text
TICKET_CREATED:<ticketId>
STATUS_HISTORY:<historyId>
MESSAGE:<messageId>
```

They are immutable, unique within the Timeline, secret-free, usable as cursor tie-breakers, and never metric labels.

---

# 11. Item Types and Rank

Sort version 1:

```text
0 = TICKET_CREATED
1 = STATUS_CHANGED
2 = PUBLIC_REQUESTER_MESSAGE
3 = PUBLIC_SUPPORT_MESSAGE
4 = INTERNAL_SUPPORT_NOTE
```

New types require a sort-version change.

---

# 12. Ordering

Default ordering:

```text
occurredAt ASC
itemTypeRank ASC
itemId ASC
```

The Timeline begins at Ticket creation and advances chronologically.

Item ID is the unique tie-breaker.

Client-selected direction is not supported in the MVP.

---

# 13. Snapshot Semantics

The first request creates:

```text
snapshotAt = trusted service clock
```

Every page applies:

```text
occurredAt <= snapshotAt
```

The cursor preserves the same snapshot.

Items created after the first page do not enter later pages of that cursor session.

A refreshed Timeline creates a new snapshot and includes new items.

The response declares:

```text
consistency = SNAPSHOT
```

Corrections create new current-time items rather than backdating rows.

---

# 14. Page Size

```text
default = 50
minimum = 1
maximum = 100
```

Invalid values return `400 VALIDATION_ERROR`.

---

# 15. Cursor Pagination

The cursor binds:

- Operation
- Ticket
- Actor
- Scope
- View type
- Visibility policy version
- Snapshot
- Sort version
- Last occurred time
- Last item-type rank
- Last item ID
- Issue and expiration time

Recommended encoding:

```text
base64url(payload) + "." + base64url(HMAC-SHA-256(payload))
```

TTL is 24 hours.

Malformed, modified, expired, Ticket-mismatched, actor-mismatched, scope-mismatched, view-mismatched, policy-version-mismatched, sort-version-mismatched, or operation-mismatched cursors return `400 INVALID_CURSOR`.

Permission changes invalidate old cursors.

---

# 16. Keyset Predicate

The query advances lexicographically across:

```text
occurredAt
itemTypeRank
itemId
```

and applies the fixed snapshot upper bound.

It reads `limit + 1` rows to determine `hasMore`.

---

# 17. Query Architecture

```text
TicketTimelineController
→ GetTicketTimelineUseCase
→ GetTicketTimelineApplicationService
→ TicketTimelineQueryPort
→ JdbcTicketTimelineQueryAdapter
→ PostgreSQL UNION ALL Projection
```

Use explicit JDBC projections and SQL visibility predicates.

Do not rehydrate the Ticket aggregate, use lazy JPA graphs, load internal rows for Employee views, call remote services, or mutate business data.

---

# 18. SQL Projection Shape

The query uses `UNION ALL` over Ticket creation, Status History, and Messages.

Employee SQL includes `m.visibility = 'PUBLIC'`.

Support internal SQL may include internal rows only after authorization.

The projection applies the snapshot, keyset predicate, stable ordering, and bounded limit.

---

# 19. Employee Timeline Response

Employee items may include:

- Stable item ID
- Item type
- Public visibility
- Occurred time
- Safe actor label
- Safe summary
- Public message content
- Public status metadata
- Related aggregate version

Employee items exclude actor IDs, internal actor references, internal notes, internal reasons, workflow IDs, Audit metadata, and Tool or Approval internals.

Safe actor labels include `You`, `IT Support`, and `System`.

---

# 20. Support Timeline Response

Support public view contains only public items.

Support internal view may additionally contain internal notes, approved internal reason codes, and pseudonymous actor references.

It still excludes JWTs, credentials, Tool secrets, full Audit records, unapproved identity attributes, and raw event payloads.

---

# 21. Auditor Policy View

Auditors require approved audit scope.

Fields are selected by `AUDITOR_POLICY_VIEW`.

Message content is redacted by default unless explicitly approved.

Auditor Timeline reads always create Security Audit.

A dedicated Auditor API may be specified later.

---

# 22. Empty Timeline

A normal Ticket has at least Ticket-created and initial-status items.

For migration or repair cases, an existing authorized Ticket with no sources returns `200` with an empty item array, not `404`.

---

# 23. Sensitive-read Audit

Normal Employee self-read uses standard logs, traces, and metrics without a Business Audit row by default.

Support responses containing internal items or sensitive internal fields create an append-only `TICKET_TIMELINE_VIEWED` sensitive-read Audit record.

Audit excludes item ID lists, message content, summaries, cursors, response bodies, and JWTs.

Required Audit failure returns `500 INTERNAL_ERROR` and no sensitive response body.

Auditor reads always create Security Audit.

---

# 24. Error Contract

| Scenario | HTTP | Code |
|---|---:|---|
| Invalid Ticket ID or limit | 400 | `VALIDATION_ERROR` |
| Invalid or expired cursor | 400 | `INVALID_CURSOR` |
| Missing or invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing coarse scope | 403 | `FORBIDDEN` |
| Ticket absent or hidden | 404 | `TICKET_NOT_FOUND` |
| Required Audit failure | 500 | `INTERNAL_ERROR` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Errors never reveal hidden resource existence, internal item existence, actor scope, cursor payloads, SQL, message content, JWTs, or secrets.

---

# 25. Observability

Recommended spans cover view resolution, authorization, cursor decode and validation, database projection, sensitive-read Audit, and cursor encoding.

Metrics include Timeline query count, duration, result count, invalid cursors, authorization denial, internal views, and Audit failure.

Allowed labels are low-cardinality actor type, view type, result, status class, cursor presence, has-more, and internal inclusion.

Ticket IDs, item IDs, actor IDs, requester IDs, and raw cursors are forbidden metric labels.

Logs never contain Timeline bodies, message content, internal reasons, cursors, JWTs, or secrets.

---

# 26. Performance

Targets:

```text
p95 < 400 ms
p99 < 1.2 s
```

Requirements:

- Maximum page size 100
- One main `UNION ALL` query
- At most one required Audit insert
- No count query
- No Offset
- No N+1
- Employee query does not read internal rows
- Bounded response
- Source indexes

---

# 27. Index Strategy

Evaluate:

```text
ticket.tickets(ticket_id)
ticket.ticket_status_history(ticket_id, occurred_at ASC, history_id ASC)
ticket.ticket_messages(ticket_id, created_at ASC, message_id ASC)
```

An internal-view index on visibility is added only with query-plan evidence.

Use representative data and `EXPLAIN (ANALYZE, BUFFERS)`.

---

# 28. Tests First

Tests cover application mapping, ownership, Support scope, visibility, Auditor policy, cursor integrity, snapshot behavior, controller contract, PostgreSQL projection, ordering, tie-breakers, query plans, required Audit, redaction, and non-mutation.

Planned names are listed in `traceability-entry.yaml`.

---

# 29. Package Mapping

```text
ticket.api.publicapi
├── TicketTimelineController
├── EmployeeTimelineResponse
├── EmployeeTimelineItemResponse
└── EmployeeTimelineApiMapper

ticket.api.support
├── SupportTicketTimelineController
├── SupportTimelineResponse
├── SupportTimelineItemResponse
└── SupportTimelineApiMapper

ticket.application.port.in
└── GetTicketTimelineUseCase

ticket.application.query
├── TicketTimelineQuery
├── TicketTimelineResult
├── TicketTimelineItem
├── TicketTimelineCursor
├── TicketTimelineViewType
├── TicketTimelineSortVersion
└── TimelineSnapshot

ticket.application.policy
└── TicketTimelineViewPolicy

ticket.application.service
└── GetTicketTimelineApplicationService

ticket.application.port.out
├── TicketTimelineQueryPort
└── SensitiveReadAuditPort

ticket.infrastructure.query
├── JdbcTicketTimelineQueryAdapter
├── TicketTimelineProjection
├── TicketTimelineCursorCodec
├── TicketTimelineCursorSigner
└── TicketTimelineQuerySql
```

---

# 30. Traceability

The planned entry is in `traceability-entry.yaml`.

After implementation, merge it into the Ticket Workflow traceability matrix and replace planned IDs, classes, tests, indexes, and policy versions with actual values.

---

# 31. Definition of Done

- [ ] Employees read only public items from owned Tickets.
- [ ] Support reads only authorized Tickets.
- [ ] Internal notes require a separate scope.
- [ ] The server selects the view.
- [ ] Authorization and visibility are pushed into SQL.
- [ ] Employee queries do not read internal-note rows.
- [ ] Timeline source mapping is correct.
- [ ] Ordering is `occurredAt ASC, itemTypeRank ASC, itemId ASC`.
- [ ] Cursor uses keyset pagination.
- [ ] Cursor binds Ticket, actor, scope, view, snapshot, and sort.
- [ ] One cursor session uses a fixed snapshot.
- [ ] New items do not enter an older snapshot.
- [ ] Empty Timeline returns 200.
- [ ] Employee responses exclude actor IDs, internal reasons, and internal notes.
- [ ] Sensitive Support read Audit passes.
- [ ] Required Audit failure closes the read.
- [ ] Query does not mutate Ticket, History, or Message rows.
- [ ] Query emits no Outbox event.
- [ ] No Offset, count query, or N+1.
- [ ] Query plan meets the baseline.
- [ ] Schema, cursor, visibility, Audit, and redaction tests pass.
- [ ] PostgreSQL, ArchUnit, `./mvnw clean verify`, and CI pass.
- [ ] Traceability is updated.

---

# 32. Business Guarantee

```text
Employees and Support view a unified Ticket Timeline within strict authorization boundaries.
Employees never see internal notes or security metadata.
One cursor session has a stable snapshot and deterministic order.
Sensitive Timeline reads are auditable,
and the query never changes Ticket lifecycle data.
```
