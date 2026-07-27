# SPEC-TW-005 — Support Queue Query

> **Spec ID:** SPEC-TW-005  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Actors:** IT_SUPPORT, IT_ADMIN, IT_MANAGER  
> **API:** `GET /api/v1/support/tickets`  
> **Dependencies:** SPEC-TW-001, SPEC-TW-002, and SPEC-TW-003  
> **Business Events:** None; a pure query emits no Outbox event

---

# 1. Purpose

Defines the complete behavior for IT Support querying Tickets within an authorized working Queue.

```text
Authenticated Support Actor
→ tickets:read:queue
→ Trusted Queue Scope
→ SQL-level Resource Authorization
→ Whitelisted Filters
→ SLA/Priority Ranking
→ Signed Keyset Cursor
→ Minimal Support Summaries
```

The system guarantees:

- Only approved Support roles call the API.
- Support reads only Tickets within authorized Application, Team, Tenant, Region, and sensitivity scope.
- Resource authorization is part of SQL, not Java post-filtering.
- The default Queue contains only non-terminal Tickets.
- Ordering is deterministic and has a unique tie-breaker.
- One pagination session uses a fixed SLA `evaluationTime`.
- Cursors are tamper-resistant and bind filter, sort, scope, operation, and actor.
- Responses minimize sensitive data.
- Queries are bounded and use no Offset, total count, or N+1.
- Queries never mutate Tickets or emit History or Outbox events.

---

# 2. Scope

Included:

- Support authentication
- `tickets:read:queue`
- Support resource scope
- Default non-terminal Queue
- Status, priority, application, assignment, SLA, and date filters
- Deterministic sorting
- Keyset cursor pagination
- Cursor scope binding
- Minimal Support Ticket summaries
- Empty Queue
- Filter-scope validation
- Query-audit policy hook
- Metrics, traces, safe logs
- PostgreSQL projection and query-plan tests

Excluded:

- Assignment or claim commands
- Escalation and triage
- Full Ticket detail
- Message content
- Timeline
- Full-text or semantic search
- Arbitrary query language
- Offset pagination
- Total count
- CSV or export
- Historical snapshot export
- Dashboard aggregation

---

# 3. HTTP Contract

```http
GET /api/v1/support/tickets
Authorization: Bearer <JWT>
Accept: application/json
```

Supported query parameters:

```text
limit
cursor
status
priority
applicationCode
assignedTeam
assignedAgent
unassignedOnly
slaState
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

# 4. Authentication and Role

Allowed roles:

```text
IT_SUPPORT
IT_ADMIN
IT_MANAGER
```

Invalid JWT returns `401 UNAUTHENTICATED`.

Required scope:

```text
tickets:read:queue
```

Missing scope returns `403 FORBIDDEN`.

---

# 5. Trusted Queue Scope

Queue scope comes from verified JWT organization or team claims, a local authorization projection, or an approved local policy adapter.

The synchronous query never calls a remote policy service.

Recommended model:

```text
SupportQueueScope
├── allowedApplicationCodes
├── allowedTeamIds
├── allowedTenantIds
├── allowedRegions
├── maximumDataClassification
└── allowUnassigned
```

Clients cannot expand scope with query parameters.

---

# 6. SQL-level Resource Authorization

SQL includes application, tenant, region, classification, and assignment predicates derived from the trusted scope.

The implementation must not select a broad Queue and filter it later in memory.

Authorization and business filters are part of the same parameterized SQL query.

---

# 7. Filter Scope Validation

Requested filter values must be a subset of actor scope.

Example:

```text
Allowed applications = [VPN, EMAIL]
Requested applicationCode = HOUSING_PORTAL
→ 403 FILTER_OUTSIDE_AUTHORIZED_SCOPE
```

This differs from missing overall Queue permission, which returns `403 FORBIDDEN`.

When a scope dimension is absent from the request, the system applies the actor's complete approved scope.

---

# 8. Default Queue

Default condition:

```text
status NOT IN (CLOSED, CANCELLED)
```

`RESOLVED` remains visible until auto-close or manual close.

Operational states include NEW through FAILED except CLOSED and CANCELLED.

Historical terminal-state search belongs to a later search or reporting specification.

---

# 9. Filter Contract

Supported filters are status, priority, application code, assigned team, assigned agent, unassigned-only, SLA state, and creation range.

Operational Queue requests containing `CLOSED` or `CANCELLED` return `400 VALIDATION_ERROR`.

Allowed priorities:

```text
UNASSIGNED
P1
P2
P3
P4
```

`assignedTeam` and `applicationCode` values must be within actor scope.

`assignedAgent` is limited to the actor or their approved management scope.

`unassignedOnly=true` conflicts with an explicit assigned agent.

Allowed SLA states:

```text
BREACHED
AT_RISK
ACTIVE
PAUSED
COMPLETED
```

Creation range uses an inclusive lower bound and exclusive upper bound. The recommended maximum range is 180 days.

Filters are canonicalized before fingerprinting.

---

# 10. Page Size

```text
default = 25
minimum = 1
maximum = 100
```

Invalid values return `400 VALIDATION_ERROR`.

---

# 11. SLA Urgency Ranking

One pagination session uses the cursor's fixed `evaluationTime`.

Ranks:

```text
0 = BREACHED
1 = AT_RISK
2 = ACTIVE
3 = PAUSED
4 = COMPLETED
```

`AT_RISK` is calculated from local SLA policy and the fixed evaluation time.

No remote SLA call occurs inside the query.

Fixing the evaluation time prevents time passage from changing SLA rank between pages.

---

# 12. Priority Ranking and Live Consistency

```text
0 = P1
1 = P2
2 = P3
3 = P4
4 = UNASSIGNED
```

Priority, status, assignment, and SLA state can change.

Therefore the endpoint is a:

```text
LIVE operational queue
```

It guarantees cursor stability for records whose sort keys do not change and stable time-based SLA evaluation within one cursor session.

It does not promise historical snapshot consistency across business updates. Snapshot export requires a separate reporting design.

---

# 13. Default Sort

```text
slaRank ASC
priorityRank ASC
createdAt ASC
ticketId ASC
```

Breached and at-risk Tickets appear first, followed by higher priority and older Tickets. TicketId is the unique tie-breaker.

---

# 14. Cursor Pagination

The cursor contains operation, evaluation time, last sort values, filter fingerprint, scope fingerprint, actor fingerprint, sort version, issue time, and expiration.

Recommended encoding:

```text
base64url(payload) + "." + base64url(HMAC-SHA-256(payload))
```

TTL:

```text
1 hour
```

The cursor binds:

- Operation
- Actor
- Queue scope
- Filters
- Sort version
- Evaluation time

Malformed, modified, expired, actor-mismatched, scope-mismatched, filter-mismatched, or version-mismatched cursors return `400 INVALID_CURSOR`.

When permissions change between pages, the old cursor becomes invalid.

---

# 15. Keyset Predicate

The query advances lexicographically across:

```text
slaRank
priorityRank
createdAt
ticketId
```

with ascending order and `limit + 1` rows to determine `hasMore`.

The SQL rank expressions and cursor values must be identical.

---

# 16. Response Contract

The response contains:

- `items`
- bounded page metadata
- fixed `evaluationTime`
- `consistency = LIVE`
- frozen sort definition
- canonical applied filters

Each item is a minimal Support Ticket summary.

---

# 17. Support Ticket Summary

Allowed:

- TicketId
- DisplayId
- Title
- ApplicationCode
- Status
- Priority
- Pseudonymous RequesterRef
- Minimal assignment summary
- Minimal SLA summary
- CreatedAt
- UpdatedAt
- Version

Forbidden:

- Full Description
- Message or internal-note content
- Requester email
- Raw identity attributes
- Approval payload
- Tool arguments or credentials
- Verification evidence
- Reconciliation detail
- Full Audit metadata
- Secrets

Support opens `SPEC-TW-002 Get Ticket` for full authorized detail.

---

# 18. Empty Queue

No matches return:

```text
HTTP 200
items = []
hasMore = false
nextCursor = null
```

Never return `404`.

---

# 19. Query Architecture

```text
SupportTicketQueryController
→ QuerySupportQueueUseCase
→ QuerySupportQueueApplicationService
→ SupportQueueQueryPort
→ JdbcSupportQueueQueryAdapter
→ PostgreSQL Projection
```

Use explicit JDBC projections, parameterized SQL, SQL authorization, and read-only access.

Do not rehydrate the aggregate, load lazy graphs, fetch descriptions or messages, call remote services, or mutate business data.

If direct joins fail the performance target, an ADR must approve a dedicated `support_queue_projection`.

---

# 20. Index Strategy

Evaluate indexes for application, assignment, status, priority, creation time, Ticket ID, and the current SLA cycle.

Use partial indexes for non-terminal Tickets when justified.

Every index requires representative data and `EXPLAIN (ANALYZE, BUFFERS)` evidence.

---

# 21. Audit

The Queue list does not create one Audit record per returned row.

Policy may require one summary `SUPPORT_QUEUE_ACCESSED` record containing actor, scope fingerprint, filter fingerprint, result count, trace, and time.

Audit excludes Ticket ID lists, titles, requester references, cursor, and response body.

Detailed sensitive-read Audit belongs to SPEC-TW-002.

---

# 22. Observability

Recommended spans cover scope resolution, filter validation, cursor decode and validation, the database query, and cursor encoding.

Metrics include query count, duration, result count, invalid cursor, forbidden filter, and authorization denial.

Only low-cardinality labels are allowed. Team IDs, agent IDs, Ticket IDs, RequesterRefs, cursors, and scope fingerprints are forbidden labels.

---

# 23. Performance

Targets:

```text
p95 < 400 ms
p99 < 1.2 s
```

Requirements:

- Default page size 25; maximum 100
- One main query
- No count query
- No N+1
- No Offset
- Bounded response
- Scope and filter indexes
- No Description or Message load

---

# 24. Error Contract

| Scenario | HTTP | Code |
|---|---:|---|
| Invalid filter, limit, or date | 400 | `VALIDATION_ERROR` |
| Invalid or expired cursor | 400 | `INVALID_CURSOR` |
| Missing or invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing Queue scope | 403 | `FORBIDDEN` |
| Filter outside authorized scope | 403 | `FILTER_OUTSIDE_AUTHORIZED_SCOPE` |
| Rate limited | 429 | `RATE_LIMITED` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Errors never expose complete actor scope, available team lists, cursor payloads, SQL, JWT, or Ticket data.

---

# 25. Rate Limit

Recommended:

```text
120 queue requests / minute / support user
```

Full enforcement may be hardened in Phase 09, while the contract and metrics are frozen now.

---

# 26. Tests First

Application, authorization, cursor, API, PostgreSQL, privacy, and non-mutation tests must cover:

```text
QuerySupportQueueApplicationServiceTest
SupportQueueFilterValidationTest
SupportQueueSortRankingTest
SupportQueueScopeAuthorizationIT
SupportQueueFilterScopeTest
SupportQueueCrossTenantIsolationIT
SupportQueueCursorCodecTest
SupportQueueCursorSignatureTest
SupportQueueCursorExpiryTest
SupportQueueCursorScopeBindingTest
SupportQueueCursorActorBindingTest
SupportQueueEvaluationTimeTest
SupportQueueControllerTest
SupportQueueProjectionIT
SupportQueueDefaultStateFilterIT
SupportQueueFilterIT
SupportQueueStableSortIT
SupportQueueKeysetPaginationIT
SupportQueueQueryPlanIT
SupportQueueFieldVisibilityTest
SupportQueueTelemetryRedactionTest
SupportQueueDoesNotMutateTicketIT
SupportQueueDoesNotCreateOutboxIT
```

---

# 27. Package Mapping

```text
ticket.api.support
├── SupportTicketQueryController
├── SupportQueueResponse
├── SupportTicketSummaryResponse
├── SupportQueuePageResponse
└── SupportQueueApiMapper

ticket.application.port.in
└── QuerySupportQueueUseCase

ticket.application.query
├── SupportQueueQuery
├── SupportQueueFilters
├── SupportQueueResult
├── SupportTicketSummary
├── SupportQueueCursor
├── SupportQueueScope
└── SupportQueueSortVersion

ticket.application.service
└── QuerySupportQueueApplicationService

ticket.application.port.out
├── SupportQueueQueryPort
└── SupportQueueScopePort

ticket.infrastructure.query
├── JdbcSupportQueueQueryAdapter
├── SupportQueueProjection
├── SupportQueueCursorCodec
├── SupportQueueCursorSigner
└── SupportQueueQuerySql
```

---

# 28. Definition of Done

- [ ] Only approved Support roles can call the API.
- [ ] `tickets:read:queue` is enforced.
- [ ] Queue scope comes from trusted context.
- [ ] Authorization predicates are in SQL.
- [ ] Requested filters are scope subsets.
- [ ] Default Queue contains only non-terminal Tickets.
- [ ] Sort ranks are frozen.
- [ ] Cursor uses keyset pagination.
- [ ] Cursor binds actor, scope, filter, sort, and evaluation time.
- [ ] Permission changes invalidate old cursors.
- [ ] Empty Queue returns 200.
- [ ] Summaries exclude Description, messages, email, and secrets.
- [ ] No Offset, count query, or N+1.
- [ ] Query does not mutate Tickets or emit Outbox events.
- [ ] Query plan meets the baseline.
- [ ] Schema, security, cursor, and redaction tests pass.
- [ ] PostgreSQL, ArchUnit, `./mvnw clean verify`, and CI pass.
- [ ] Traceability is updated.

---

# 29. Business Guarantee

```text
IT Support can quickly view the most urgent Tickets within authorized scope
without crossing Queue, Tenant, Team, or data boundaries.
Ordering and pagination follow deterministic rules,
SLA time evaluation remains stable during one cursor session,
and the query never mutates the Ticket lifecycle.
```
