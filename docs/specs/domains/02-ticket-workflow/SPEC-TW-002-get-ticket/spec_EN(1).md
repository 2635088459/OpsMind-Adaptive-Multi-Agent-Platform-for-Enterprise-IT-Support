# SPEC-TW-002 — Get Ticket

> **Spec ID:** SPEC-TW-002  
> **Domain:** `02-ticket-workflow`  
> **Feature:** Get Ticket  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Primary Actors:** EMPLOYEE, IT_SUPPORT, IT_ADMIN, IT_MANAGER, AUDITOR  
> **API:** `GET /api/v1/tickets/{ticketId}`  
> **Use Case:** UC-02 Get Ticket  
> **API Contract:** API-002 Get Ticket  
> **Code Directory:** `services/ticket-workflow-service/`

---

# 1. Purpose

This specification defines the complete behavior required when an authorized actor reads the current details of one Ticket by Ticket ID.

It converts the following design concerns into a testable vertical slice:

```text
Ticket Read Model
+ Resource Ownership
+ Support Scope Authorization
+ Field Visibility
+ Conditional GET
+ Sensitive Read Audit
+ Query Observability
```

Developers use this specification as the primary implementation document. Referenced architecture, security, data, and test details remain governed by the Ticket Workflow LLD.

---

# 2. Business Outcome

Successful execution follows:

```text
Authorized Actor
→ GET /api/v1/tickets/{ticketId}
→ Authentication
→ Coarse-grained Scope Check
→ Resource-level Authorization in Query
→ Actor-specific Projection
→ Optional Sensitive Read Audit
→ HTTP 200 + ETag
```

The system guarantees:

- Employees can read only Tickets they created.
- Support can read only Tickets within authorized support scope.
- Returned fields depend on actor type and scope.
- Employees never receive internal fields.
- Unauthorized resource access does not reveal whether a Ticket exists.
- The query never changes Ticket state, version, or `updatedAt`.
- The query does not rebuild the full Ticket aggregate.
- PostgreSQL projections are used.
- `If-None-Match` is supported.
- Sensitive Support reads meet audit requirements.

---

# 3. Specification Boundary

## Included

- Get one Ticket by internal `ticketId`
- JWT authentication
- Scope authorization
- Employee resource ownership
- Support resource scope
- Employee view
- Support view
- Auditor view policy hook
- SQL-level authorization filtering
- Field-level visibility
- ETag
- `If-None-Match`
- `304 Not Modified`
- Safe `404`
- Sensitive-read audit
- Query telemetry
- JSON Schema validation
- Unit, controller, security, and PostgreSQL integration tests

## Excluded

- Search by display ID
- Ticket lists
- Support Queue lists
- Ticket Timeline
- Message lists
- Add message
- Attachment download
- Ticket mutation
- Assignment mutation
- Triage
- Approval
- Tool execution
- Verification
- Full-text search
- Semantic search
- WebSocket updates
- Response caching
- GraphQL

---

# 4. Design References

## Core Mapping

| Type | Reference |
|---|---|
| Use Case | UC-02 Get Ticket |
| API | API-002 Get Ticket |
| Domain | Ticket identity, requester, status, priority, assignment, SLA |
| Data Model | `ticket.tickets`, Resolution Cycle, SLA Cycle |
| Security | Resource ownership, support scope, field visibility |
| Error Handling | Not Found, Forbidden, invalid ID, safe error envelope |
| Observability | Ticket read, authorization denied, sensitive-read audit |
| Package Design | Query service, query port, JDBC projection adapter |
| Testing | Query, security, visibility, PostgreSQL integration |

## Applied Constraints

This specification guarantees at minimum:

```text
TicketId is unique and immutable
RequesterId is immutable
Queries never cause state transitions
Employees read only owned resources
Support reads only authorized resources
Internal fields never reach Employees
Resource-level denial is hidden as Not Found
Audit records are append-only
Metric labels exclude TicketId and RequesterId
Logs and traces exclude Description, JWT, and secrets
```

The final traceability entry must replace these descriptions with the exact frozen LLD IDs.

---

# 5. Actor and View Resolution

The server selects the view from the trusted principal.

The client cannot request a higher-privilege view through:

```text
?view=support
X-View-Type: INTERNAL
role in request
scope in request
```

Resolution:

```text
EMPLOYEE
→ EMPLOYEE_VIEW

IT_SUPPORT / IT_ADMIN / IT_MANAGER
→ SUPPORT_VIEW when resource scope permits

AUDITOR
→ AUDITOR_VIEW when approved audit scope permits
```

When a principal has multiple roles, an explicit server policy applies. The service does not automatically return the broadest view.

---

# 6. HTTP Contract

## Endpoint

```http
GET /api/v1/tickets/{ticketId}
```

## Path Parameter

`ticketId` rules:

- Canonical UUID string.
- The MVP generates UUIDv7, while API validation accepts canonical UUID representation.
- Empty, malformed, or path-injected IDs return `400 VALIDATION_ERROR`.
- Display ID lookup is outside this specification.

## Required Headers

```http
Authorization: Bearer <JWT>
Accept: application/json
```

## Optional Headers

```http
If-None-Match: "<version>"
traceparent: <W3C trace context>
X-Correlation-Id: <1-128 characters>
```

## Response Headers

Success:

```http
ETag: "<ticket-version>"
Cache-Control: private, no-store
Vary: Authorization
Content-Type: application/json
```

Conditional match:

```http
HTTP 304 Not Modified
ETag: "<ticket-version>"
Cache-Control: private, no-store
Vary: Authorization
```

A `304` response has no body.

---

# 7. Authentication

Validate:

- Signature
- Issuer
- Audience
- Expiration
- Not Before
- Authorized Party
- Token Type
- Subject
- Environment

Failure:

```text
HTTP 401
UNAUTHENTICATED
```

The error response does not expose whether the token was absent, expired, or cryptographically invalid.

---

# 8. Coarse-grained Authorization

## Employee

Require an approved scope such as:

```text
tickets:read:self
```

## Support

Require an approved scope such as:

```text
tickets:read:queue
```

## Auditor

Require an approved scope such as:

```text
tickets:audit:read
```

Missing coarse read scope:

```text
HTTP 403
FORBIDDEN
```

The actor has read scope but the resource is outside its authorized scope:

```text
HTTP 404
TICKET_NOT_FOUND
```

---

# 9. Resource-level Authorization

Resource authorization is pushed into SQL wherever possible.

## Employee Predicate

```sql
WHERE ticket_id = :ticketId
  AND requester_id = :principalSubject
```

Do not:

```text
read by TicketId first
→ check requester later in Java
```

## Support Predicate

Includes at minimum:

```text
ticketId
+
allowed application codes
+
allowed support teams or queues
+
tenant or region where applicable
+
sensitivity policy
```

Support scope comes from trusted security context or an approved local authorization projection.

This synchronous query does not call a remote policy service.

## Auditor Predicate

Auditors receive only policy-approved fields and resources.

Auditor View is not equivalent to Support View.

---

# 10. Resource Hiding

Return:

```text
HTTP 404
TICKET_NOT_FOUND
```

when:

- The Ticket does not exist.
- An Employee requests another user's Ticket.
- Support requests a Ticket outside authorized scope.
- An Auditor requests an unapproved resource.
- Tenant isolation hides the Ticket.

Never return:

```text
The Ticket exists but you do not have access
```

---

# 11. Query Architecture

Use lightweight CQRS:

```text
Controller
→ GetTicketUseCase
→ GetTicketApplicationService
→ TicketQueryPort
→ JdbcTicketQueryAdapter
→ PostgreSQL Projection
→ Actor-specific DTO
```

Rules:

- Do not load the full Ticket aggregate.
- Do not assemble large lazy JPA graphs.
- Do not return JPA entities.
- Use explicit SQL or Spring JDBC projections.
- Select only fields required by the view.
- Employee and Support may use different projections.
- Use parameterized SQL.
- Default isolation is `READ COMMITTED`.
- Employee self-read is read-only or has no explicit transaction.
- Do not update `updatedAt`, version, or last activity.

---

# 12. Employee View

Schema:

```text
schemas/employee-ticket-response.schema.json
```

Example:

```json
{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "status": "NEW",
  "priority": "UNASSIGNED",
  "createdAt": "2026-07-23T16:30:00Z",
  "updatedAt": "2026-07-23T16:30:00Z",
  "version": 0,
  "sla": {
    "state": "ACTIVE",
    "responseDueAt": "2026-07-23T20:30:00Z",
    "resolutionDueAt": "2026-07-24T16:30:00Z"
  },
  "links": {
    "self": "/api/v1/tickets/018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
    "timeline": "/api/v1/tickets/018f0f1e-7b31-7a00-8f42-31f9b25b1a91/timeline",
    "messages": "/api/v1/tickets/018f0f1e-7b31-7a00-8f42-31f9b25b1a91/messages"
  }
}
```

Allowed:

- TicketId
- DisplayId
- Title
- Description
- ApplicationCode
- Source
- Status
- Priority
- Public SLA summary
- CreatedAt
- UpdatedAt
- Version
- Safe links

Forbidden:

- Internal requester identifier
- Requester email
- Internal messages or notes
- Internal assignment IDs
- Support actor IDs
- Risk score
- Security flags
- Approval internals
- Tool request or execution details
- Verification internals
- Reconciliation metadata
- Audit metadata
- Active workflow internal ID
- Secrets or credentials

---

# 13. Support View

Schema:

```text
schemas/support-ticket-response.schema.json
```

Support View may add:

```text
requesterRef
assignment
resolutionCycle
internal SLA summary
active workflow summary
internal classification summary
```

Only fields required for the current support task are returned.

Example:

```json
{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "status": "NEW",
  "priority": "UNASSIGNED",
  "requesterRef": "usr_7f2d8a",
  "assignment": {
    "teamId": null,
    "agentId": null,
    "queue": "HOUSING_PORTAL"
  },
  "resolutionCycle": {
    "cycleNumber": 1,
    "status": "ACTIVE"
  },
  "sla": {
    "state": "ACTIVE",
    "policyId": "SLA-STANDARD-P2",
    "responseDueAt": "2026-07-23T20:30:00Z",
    "resolutionDueAt": "2026-07-24T16:30:00Z"
  },
  "createdAt": "2026-07-23T16:30:00Z",
  "updatedAt": "2026-07-23T16:30:00Z",
  "version": 0
}
```

Still forbidden:

- Passwords
- Tokens
- Session cookies
- API keys
- Tool credentials
- Requester secrets
- Full audit records
- Unauthorized tenant or region fields
- Internal fields unrelated to the current role

---

# 14. Auditor View

This specification defines only the Auditor policy hook, not a complete dedicated audit API.

Minimum rules:

- Auditor role does not automatically grant full Description access.
- `AUDITOR_VIEW` policy selects fields.
- The audit read itself creates Security Audit.
- Unapproved sensitive fields are omitted or masked.
- A dedicated Auditor API may be specified later.

---

# 15. Conditional GET

## ETag

```text
ETag = quoted decimal aggregate version
```

Example:

```http
ETag: "0"
```

## If-None-Match

When:

```text
If-None-Match == current ETag
```

return:

```text
HTTP 304 Not Modified
```

with no body.

## Authorization First

Authentication, scope authorization, and resource authorization run before returning `304`.

A conditional response never leaks resource existence or version.

## Audit

Sensitive Support and Auditor conditional reads remain access attempts and follow audit policy.

---

# 16. Sensitive-read Audit

## Employee Self-read

Normal Employee self-read:

- Produces standard logs, traces, and metrics.
- Does not create a high-cost Business Audit row by default.
- Security anomalies and denials may still generate Security Audit.

## Support Sensitive Read

When Support reads sensitive Ticket detail, append:

```text
auditType = SENSITIVE_READ
action = TICKET_VIEWED
actorType
actorId
clientId
resourceType = TICKET
resourceId = ticketId
viewType = SUPPORT_VIEW
fieldsPolicyVersion
traceId
outcome
occurredAt
```

Audit excludes:

- Title
- Description
- Response body
- JWT
- Raw scopes

## Fail Closed

When policy marks the read audit as required and insertion fails:

```text
The read fails closed
```

Return:

```text
HTTP 500
INTERNAL_ERROR
```

Sensitive detail is never returned without required audit.

---

# 17. Data Classification and Response Security

| Field | Classification |
|---|---|
| TicketId / DisplayId | INTERNAL |
| Title / Description | SENSITIVE |
| RequesterRef | SENSITIVE |
| ApplicationCode / Source | INTERNAL |
| Status / Priority | INTERNAL |
| SLA Summary | INTERNAL |
| Internal Assignment | INTERNAL / SENSITIVE |
| Workflow Metadata | INTERNAL |
| Secrets / Credentials | SECRET and forbidden |

Headers:

```http
Cache-Control: private, no-store
Pragma: no-cache
X-Content-Type-Options: nosniff
```

Shared CDNs and public caches must not store the response.

---

# 18. Error Contract

Schema:

```text
schemas/error-envelope.schema.json
```

Envelope:

```json
{
  "error": {
    "code": "TICKET_NOT_FOUND",
    "message": "The Ticket was not found.",
    "traceId": "8f03aabbccddeeff0011223344556677",
    "correlationId": "corr-get-ticket-001",
    "details": {}
  }
}
```

## Error Matrix

| Scenario | HTTP | Error Code |
|---|---:|---|
| Invalid Ticket UUID | 400 | `VALIDATION_ERROR` |
| Missing or invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing coarse read scope | 403 | `FORBIDDEN` |
| Ticket absent | 404 | `TICKET_NOT_FOUND` |
| Resource outside actor scope | 404 | `TICKET_NOT_FOUND` |
| Required read audit fails | 500 | `INTERNAL_ERROR` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Errors never expose:

- Stack trace
- SQL
- Table name
- Constraint name
- Actor permissions
- Whether a hidden Ticket exists
- JWT
- Connection strings

---

# 19. Observability

## Traces

Recommended:

```text
HTTP GET /api/v1/tickets/{ticketId}
GetTicketUseCase
ticket.authorization.resource
db.ticket.employee_view
db.ticket.support_view
db.audit.sensitive_read
```

Allowed bounded attributes:

```text
operation = get_ticket
actor_type
view_type
result
status
application_code when known and bounded
audit_required
conditional_request
```

Forbidden:

```text
title
description
requesterId
JWT
raw scopes
ticketId as a metric label
```

TicketId may be a controlled trace attribute but never a metric label.

## Metrics

At minimum:

```text
opsmind_ticket_get_total
opsmind_ticket_get_duration_seconds
opsmind_ticket_get_not_found_total
opsmind_ticket_get_authorization_denied_total
opsmind_ticket_get_not_modified_total
opsmind_ticket_sensitive_read_audit_failure_total
```

Allowed labels:

```text
actor_type
view_type
result
status_class
conditional_request
```

Forbidden labels:

```text
ticketId
requesterId
traceId
correlationId
clientId
```

## Logging

Allowed:

```text
traceId
correlationId
operation
actorType
viewType
result
duration
safe error code
```

Do not log:

- Response body
- Title
- Description
- JWT
- Raw requester identity
- TicketId at INFO unless policy permits

---

# 20. Performance and Query Requirements

Targets:

```text
Get Ticket p95 < 300 ms
Get Ticket p99 < 1 s
```

Requirements:

- One main projection query.
- At most one required audit insert.
- No N+1.
- Do not load messages.
- Do not load timeline items.
- Do not load full audit history.
- Query plan uses Ticket PK and authorization indexes.
- PostgreSQL integration tests verify correctness.
- Query-plan tests detect obvious full-table scan regressions.

---

# 21. Response Schema Files

Included:

```text
schemas/
├── employee-ticket-response.schema.json
├── support-ticket-response.schema.json
└── error-envelope.schema.json
```

Requirements:

- JSON Schema Draft 2020-12.
- `additionalProperties = false`.
- Dates use `date-time`.
- Ticket IDs use `uuid`.
- Enums align with the API contract.
- Employee schema excludes internal fields.
- Support schema excludes secrets.

---

# 22. Acceptance Scenarios

Executable scenarios are in:

```text
acceptance.feature
```

Minimum coverage:

1. Employee reads an owned Ticket.
2. Employee reads another user's Ticket and receives 404.
3. Support reads a Ticket in an authorized queue.
4. Support reads a Ticket outside scope and receives 404.
5. Missing read scope returns 403.
6. Missing Ticket returns 404.
7. Invalid Ticket ID returns 400.
8. Employee response excludes internal fields.
9. Support sensitive read creates Audit.
10. Required Audit failure closes the read.
11. ETag is correct.
12. Matching `If-None-Match` returns 304.
13. Authorization runs before 304.
14. Query does not mutate version or `updatedAt`.
15. Query emits no business event or Outbox record.

---

# 23. Tests First

## Application RED

```text
GetTicketApplicationServiceTest
GetTicketViewPolicyTest
GetTicketConditionalRequestTest
```

## API RED

```text
GetTicketControllerTest
GetTicketInvalidIdTest
GetTicketErrorContractTest
GetTicketNotModifiedTest
```

## Security RED

```text
GetTicketRequesterOwnershipTest
GetTicketMissingScopeTest
GetTicketSupportAuthorizationTest
GetTicketResourceHidingTest
GetTicketFieldVisibilityTest
GetTicketAuditorPolicyTest
```

## PostgreSQL Integration RED

```text
GetTicketEmployeeProjectionIT
GetTicketSupportProjectionIT
GetTicketResourceScopeQueryIT
GetTicketQueryPlanIT
```

## Audit and Privacy RED

```text
GetTicketSensitiveReadAuditIT
GetTicketAuditFailureIT
GetTicketResponseRedactionTest
GetTicketTelemetryRedactionTest
```

## Non-mutation RED

```text
GetTicketDoesNotMutateTicketIT
GetTicketDoesNotCreateOutboxIT
```

---

# 24. Package and Class Mapping

Recommended:

```text
ticket.api.publicapi
├── PublicTicketQueryController
├── EmployeeTicketDetailResponse
└── PublicTicketQueryApiMapper

ticket.api.support
├── SupportTicketQueryController
├── SupportTicketDetailResponse
└── SupportTicketQueryApiMapper

ticket.application.port.in
└── GetTicketUseCase

ticket.application.query
├── GetTicketQuery
├── GetTicketResult
├── TicketViewType
└── ConditionalGetResult

ticket.application.service
└── GetTicketApplicationService

ticket.application.policy
├── TicketViewPolicy
└── TicketResourceAccessPolicy

ticket.application.port.out
├── TicketQueryPort
└── SensitiveReadAuditPort

ticket.infrastructure.query
├── JdbcTicketQueryAdapter
├── EmployeeTicketProjection
├── SupportTicketProjection
└── TicketQuerySql

ticket.infrastructure.audit
└── SensitiveReadAuditAdapter
```

Dependency direction:

```text
API
→ Application
→ Port
← Infrastructure
```

Forbidden:

```text
Controller → JdbcTemplate
Controller → JPA Repository
Application → Repository Implementation
Query DTO → JPA Entity
```

---

# 25. Traceability

The planned entry is in:

```text
traceability-entry.yaml
```

After implementation, merge it into:

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

Final traceability uses actual:

- Use-case IDs
- API IDs
- Business-invariant IDs
- Security-rule IDs
- Class names
- Test names

---

# 26. Definition of Done

`SPEC-TW-002` is complete only when:

- [ ] The Spec is reviewed.
- [ ] Phase 01 is complete.
- [ ] `GET /api/v1/tickets/{ticketId}` returns the correct actor view.
- [ ] Employees can read only owned Tickets.
- [ ] Support can read only authorized resources.
- [ ] Resource-level denial returns safe 404.
- [ ] Employee schema contains no internal fields.
- [ ] Support schema contains no secrets.
- [ ] ETag matches version.
- [ ] Matching `If-None-Match` returns 304.
- [ ] Authentication and authorization run before 304.
- [ ] The query does not mutate Ticket state.
- [ ] The query creates no Outbox event.
- [ ] Required sensitive-read Audit is correct.
- [ ] Required Audit failure closes the read.
- [ ] PostgreSQL projection tests pass.
- [ ] Query-plan tests pass.
- [ ] No N+1 exists.
- [ ] Error contract tests pass.
- [ ] Telemetry-redaction tests pass.
- [ ] ArchUnit passes.
- [ ] `./mvnw clean verify` passes.
- [ ] CI passes.
- [ ] Traceability is updated.
- [ ] The README curl example works.

---

# 27. Business Guarantee After Completion

After implementation, OpsMind guarantees:

```text
Every actor sees only the Ticket and fields they are authorized to read;
unauthorized access does not reveal whether a Ticket exists;
the query is fast, stable, auditable, and never mutates Ticket state.
```
