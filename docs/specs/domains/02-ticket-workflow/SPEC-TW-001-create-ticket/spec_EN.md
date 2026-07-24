# SPEC-TW-001 — Create Ticket

> **Spec ID:** SPEC-TW-001  
> **Domain:** `02-ticket-workflow`  
> **Feature:** Create Ticket  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Phase:** Phase 01 — Create Ticket Vertical Slice  
> **Primary Actor:** EMPLOYEE  
> **API:** `POST /api/v1/tickets`  
> **Use Case:** UC-01  
> **State Transition:** SM-001 `Initial → NEW`  
> **Published Event:** PUB-001 `ticket.created` / `ticket.created.v1`  
> **Code Directory:** `services/ticket-workflow-service/`

---

# 1. Purpose

This specification defines the complete required behavior when an Employee creates a new IT-support Ticket through the OpsMind Employee Portal.

It is the executable bridge between:

```text
Ticket Workflow LLD
→ Phase 01 Implementation Plan
→ Tests
→ Java / Spring Boot Implementation
```

Developers use this specification as the primary implementation document and return to referenced LLD sections for deeper design details.

---

# 2. Business Outcome

When an authorized Employee submits a valid request, the system must:

1. Validate the JWT and `tickets:create` scope.
2. obtain RequesterId from the JWT subject.
3. Validate and normalize the request.
4. Enforce idempotency using Actor Scope and `Idempotency-Key`.
5. Create a Ticket with initial status `NEW`.
6. Create the first Resolution Cycle.
7. Create the first SLA Cycle.
8. Create the initial Status History record.
9. Create a local Business Audit record.
10. Create a `ticket.created.v1` Outbox record.
11. Store a stable idempotent response.
12. Commit all required local records atomically in PostgreSQL.
13. Return `201 Created`.

Successful outcome:

```text
One business intent
→ Exactly one Ticket
→ One active Resolution Cycle
→ One active SLA Cycle
→ One initial History
→ One local Audit Record
→ One ticket.created Outbox Event
→ One completed Idempotency Record
```

---

# 3. Specification Boundary

## Included

- Employee Portal Ticket creation
- API validation
- Authentication
- Authorization
- Mass-assignment protection
- Ticket domain creation
- Initial Resolution Cycle
- Initial SLA Cycle
- Initial Status History
- API idempotency
- Local Business Audit
- Transactional Outbox record
- Stable HTTP response
- Error contract
- Tracing, logging, and metrics
- Unit, integration, security, contract, and concurrency tests

## Excluded

- IT Support creating a Ticket on behalf of a user
- Service-account Ticket creation
- Email ingestion
- Attachment upload
- Ticket queries
- Ticket messages
- Agent triage
- RabbitMQ consumers
- Approval
- Tool execution
- Verification
- Resolution
- Close, reopen, or cancel
- Full generic Outbox Publisher reliability implementation
- Full Keycloak realm setup
- Dashboards and alerts

Although UC-01 lists `EMPLOYEE`, `IT_SUPPORT`, and `AUTHORIZED_SERVICE`, this Phase 01 specification deliberately narrows the slice to:

```text
EMPLOYEE through the Public Employee API
```

Other actor variants require separate Feature Specs or API contracts.

---

# 4. Design References

## Core Mapping

| Type | Reference |
|---|---|
| Use Case | UC-01 Create Ticket |
| API | API-001 Create Ticket |
| State Transition | SM-001 Initial → NEW |
| Published Event | PUB-001 ticket.created |
| Domain Model | Ticket, TicketResolutionCycle, TicketSla |
| Transaction | Document 08 Create Ticket Transaction |
| Idempotency | Document 09 HTTP Command Idempotency |
| Security | Document 11 Employee + `tickets:create` |
| Observability | Document 12 Create / HTTP / Audit metrics |
| Package Design | Document 13 `CreateTicketApplicationService` |
| Testing | Document 14 Create, Atomicity, and Idempotency tests |

## Business Invariants

This specification applies:

```text
BI-001  Unique Ticket ID
BI-002  Unique Display ID
BI-003  Requester required and immutable
BI-004  Created time required and immutable
BI-005  Valid Title
BI-006  Valid Initial Description
BI-007  Allowed ApplicationCode
BI-008  Valid Category/Subcategory relationship
BI-011  Status change writes History
BI-080  At most one active SLA cycle
BI-081  SLA deadline cannot precede Ticket creation
BI-082  SLA state follows Ticket policy
BI-085  Create Ticket supports Idempotency-Key
BI-087  Replay returns a stable result
BI-088  One command key cannot represent different payloads
BI-095  Ticket, History, and Outbox commit atomically
BI-097  No external call inside the DB transaction
BI-101  Secrets cannot enter the Ticket Domain
BI-102  Integration events minimize PII
BI-104  Audit is append-only
BI-105  Status History is append-only
BI-108  Commands propagate trace context
BI-109  Metric labels exclude PII and high-cardinality IDs
BI-110  Telemetry export failure does not block business commit
```

## Design Synchronization Decisions

1. **Initial Resolution Cycle and SLA Cycle are mandatory.**  
   UC-01, SM-001, Documents 07 and 08 require them.

2. **A local Business Audit record is mandatory.**  
   Document 12 defines Ticket Created as a Business Audit event. Before code merge, Documents 07 and 08 should be synchronized to include it in the Create Ticket transaction.

3. **Attachments are outside this specification.**  
   `attachmentIds` appears in package-design examples but is not part of API-001.

4. **The Outbox record is mandatory; broker publication is not a synchronous success condition.**  
   The API waits for the database commit, not a RabbitMQ publisher confirm.

5. **The 401 error code is `UNAUTHENTICATED`.**  
   This follows Document 10 canonical semantics; API-001 should later align its older `UNAUTHORIZED` wording.

---

# 5. Actor and Authorization

## Actor

```text
principalType = EMPLOYEE
```

## Authentication

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

## Authorization

Require:

```text
tickets:create
```

Missing scope:

```text
HTTP 403
FORBIDDEN
```

## Requester Identity

```text
requesterId = principal.subject
```

RequesterId must not come from:

- Request body
- Query parameter
- Custom header
- Cookie
- Hidden frontend field

## Rate Limit

Recommended:

```text
10 requests / minute / user
```

Exceeded:

```text
HTTP 429
RATE_LIMITED
```

---

# 6. HTTP Contract

## Endpoint

```http
POST /api/v1/tickets
```

## Required Headers

```http
Authorization: Bearer <JWT>
Idempotency-Key: <1-128 characters>
Content-Type: application/json
Accept: application/json
```

## Optional Trace Headers

```http
traceparent: <W3C trace context>
X-Correlation-Id: <1-128 characters>
```

When `X-Correlation-Id` is absent, the service generates one.

The Idempotency Key is never reused as a correlation ID, trace attribute, or normal log value.

---

# 7. Request Schema

```json
{
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL"
}
```

## Field Rules

| Field | Required | Rule | Classification |
|---|---:|---|---|
| `title` | yes | 1–200 after trim; no control characters | SENSITIVE |
| `description` | yes | 1–10000; not blank; safely rendered | SENSITIVE |
| `applicationCode` | yes | `HOUSING_PORTAL` / `EMAIL` / `VPN` / `OTHER` | INTERNAL |
| `source` | yes | Employee MVP only accepts `PORTAL` | INTERNAL |

## Forbidden Fields

The request schema uses:

```text
additionalProperties = false
```

The following fields produce `400 VALIDATION_ERROR`:

```text
ticketId
displayId
requesterId
status
priority
category
subcategory
assignedTeam
assignedAgent
workflowId
approvalId
resolutionCycleId
slaCycleId
createdAt
updatedAt
version
attachmentIds
```

This prevents mass assignment and requester identity injection.

---

# 8. Request Normalization

Before the idempotency hash is computed:

- Trim Title.
- Use canonical enum values for `applicationCode` and `source`.
- Sort JSON object keys lexicographically.
- Encode as UTF-8.
- Preserve the distinction between `null` and absent.
- Preserve array order.
- Do not change Description business content.
- Use stable newline normalization.
- Reject unknown fields before hashing.

Hash input:

```text
HTTP method
normalized route template
actor scope
canonical JSON body
selected semantic headers
```

Excluded:

```text
JWT
traceparent
X-Correlation-Id
request time
header order
JSON field order
```

Hash:

```text
SHA-256(canonical request)
```

---

# 9. Domain Creation Behavior

Recommended factory:

```java
Ticket.create(
    TicketId id,
    TicketDisplayId displayId,
    RequesterId requesterId,
    TicketTitle title,
    TicketDescription description,
    ApplicationCode application,
    TicketSource source,
    Instant now
)
```

Creation result:

```text
status = NEW
priority = UNASSIGNED
category = null
subcategory = null
currentAssignment = null
activeWorkflowId = null
pendingAction = null
resolution = null
resolvedAt = null
closedAt = null
cancelledAt = null
createdAt = now
updatedAt = now
version = 0
```

The Domain emits:

```text
TicketCreated
```

The Domain Event contains at least:

```text
ticketId
displayId
requesterId
applicationCode
source
createdAt
```

The Domain Event does not contain RabbitMQ routing or JSON serialization behavior.

---

# 10. ID Generation

## TicketId

- Generated by the server.
- UUIDv7 or the approved ordered UUID.
- Globally unique.
- Never a database sequence ID used across services.

## TicketDisplayId

Format:

```text
INC-<number>
```

Requirements:

- Unique within Ticket Workflow.
- Immutable.
- A unique-constraint collision permits a bounded regeneration retry.
- Retry exhaustion returns safe `INTERNAL_ERROR` and rolls back everything.

## Related IDs

Server-generated:

```text
resolutionCycleId
slaCycleId
historyId
auditId
eventId
outboxId
idempotencyRecordId
commandId
```

---

# 11. Initial Resolution Cycle

Create:

```text
cycleNumber = 1
cycleStatus = ACTIVE
workflowId = null
openedAt = ticket.createdAt
resolvedAt = null
closedAt = null
```

Ticket points to it:

```text
currentResolutionCycleId = resolutionCycleId
```

Constraints:

- At most one active Resolution Cycle per Ticket.
- It is created in the same transaction as the Ticket.
- Failure rolls back Ticket creation.

---

# 12. Initial SLA Cycle

Create:

```text
cycleNumber = 1
status = ACTIVE
ticketId = created Ticket
resolutionCycleId = initial Resolution Cycle
policyId = resolved SLA policy
createdAt = ticket.createdAt
updatedAt = ticket.createdAt
version = 0
```

Policy rules:

- Comes from approved local configuration or the local database.
- No remote SLA service is called inside the Create Ticket transaction.
- Deadlines cannot precede Ticket creation.
- A missing required default policy is a configuration failure mapped to `INTERNAL_ERROR`; all records roll back.

---

# 13. Initial Status History

Create one append-only record:

```text
fromStatus = null
toStatus = NEW
transitionId = SM-001
reasonCode = TICKET_CREATED
actorType = EMPLOYEE
actorId = principal.subject
sourceCommandId = commandId
sourceEventId = null
workflowId = null
aggregateVersion = 0
occurredAt = ticket.createdAt
```

Constraints:

- `(ticketId, aggregateVersion)` is unique.
- The application role does not perform normal UPDATE or DELETE.
- Insert failure rolls back everything.

---

# 14. Transaction Boundary

Application entry point:

```text
CreateTicketApplicationService.create(...)
```

The public method uses:

```text
@Transactional
```

Transaction order:

```text
BEGIN

1. Reserve idempotency_record as IN_PROGRESS
2. Generate Ticket and related IDs
3. Create Ticket Aggregate
4. Resolve local SLA Policy
5. Insert ticket.tickets
6. Insert ticket.ticket_resolution_cycles
7. Insert ticket.ticket_sla_cycles
8. Insert ticket.ticket_status_history
9. Insert local ticket.audit_records
10. Insert ticket.outbox_events for ticket.created
11. Complete idempotency_record with stable response
12. COMMIT
```

Any failure:

```text
ROLLBACK ALL
```

Forbidden inside the transaction:

- RabbitMQ publish
- Publisher-confirm wait
- Agent Runtime call
- Keycloak Admin call
- Approval Service call
- Tool Gateway call
- External HTTP
- LangSmith network call
- Waiting for telemetry export

Telemetry export is outside the critical business path. Export failure does not roll back a committed business transaction.

---

# 15. Idempotency

## Actor Scope

```text
user:{principal.subject}:createTicket
```

Unique constraint:

```text
actor_scope + idempotency_key
```

## TTL

```text
24 hours
```

## Stale Threshold

```text
5 minutes
```

## Behavior Matrix

| Existing Record | Payload | Result |
|---|---|---|
| none | valid | Reserve `IN_PROGRESS` and execute |
| `COMPLETED` | same hash | Return original result |
| `COMPLETED` | different hash | `409 IDEMPOTENCY_KEY_REUSED` |
| fresh `IN_PROGRESS` | same hash | `409 REQUEST_IN_PROGRESS` + `Retry-After: 1` |
| stale `IN_PROGRESS` | same hash | Reconcile; never create a second Ticket |
| `FAILED_RETRYABLE` | same hash | Controlled reservation and retry |
| `FAILED_FINAL` | same hash | Return stored final error |

## Replay Response

Replay returns:

- Original HTTP status `201`
- Same TicketId
- Same DisplayId
- Same main response body
- Same Location
- Same ETag

It may add:

```http
Idempotency-Replayed: true
```

Replay does not create a new:

- Ticket
- Resolution Cycle
- SLA Cycle
- History
- Audit record
- Outbox event

## Concurrency Goal

```text
100 concurrent requests
same actor
same Idempotency-Key
same payload
→ exactly one Ticket
```

Other requests return the same result or `REQUEST_IN_PROGRESS` while the first request executes.

---

# 16. Persistence Requirements

Phase 01 requires at least:

```text
ticket.tickets
ticket.ticket_resolution_cycles
ticket.ticket_sla_cycles
ticket.ticket_status_history
ticket.audit_records
ticket.outbox_events
ticket.idempotency_records
```

## Initial Ticket Fields

```text
requester_id = principal.subject
priority = UNASSIGNED
status = NEW
current_resolution_cycle_id = initial cycle
active_workflow_id = null
version = 0
created_by_type = EMPLOYEE
created_by_id = principal.subject
```

## Persistence Separation

Maintain:

```text
API DTO
≠ Domain Object
≠ JPA Entity
```

Forbidden:

- JPA annotations in Domain classes
- Controller access to Spring Data repositories
- Application services depending on repository implementations
- JPA entities as HTTP responses

---

# 17. Integration Event

## Event Identity

```text
eventType = ticket.created
eventVersion = 1.0
routingKey = ticket.created.v1
producer = ticket-workflow-service
aggregateType = Ticket
aggregateId = ticketId
aggregateVersion = 0
sequence = 0
partitionKey = ticketId
dataClassification = INTERNAL
```

## Payload

```json
{
  "displayId": "INC-2048",
  "requesterIdHash": "hmac-sha256:<hex>",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "initialStatus": "NEW",
  "createdAt": "2026-07-23T16:30:00Z"
}
```

Requester pseudonymization:

```text
HMAC-SHA-256(service-controlled key, requesterId)
```

Unsalted SHA-256 is not acceptable for enumerable requester identities.

## Forbidden Event Data

```text
title
description
requester email
raw requesterId
password
access token
refresh token
API key
session cookie
private key
authorization header
Idempotency-Key
```

## Outbox Requirement

The Outbox record commits atomically with the Ticket.

Synchronous API success requires:

```text
Outbox record committed
```

not:

```text
RabbitMQ publish confirmed
```

The Outbox Publisher later provides at-least-once delivery; consumers provide idempotency.

---

# 18. Business Audit

Create an append-only local Audit record:

```text
auditType = BUSINESS_ACTION
action = TICKET_CREATED
decision = ALLOWED
actorType = EMPLOYEE
actorId = principal.subject
clientId = JWT authorized party / client ID
resourceType = TICKET
resourceId = ticketId
displayId = displayId
ticketStatusBefore = null
ticketStatusAfter = NEW
traceId = current trace
commandId = commandId
outcome = SUCCESS
dataClassification = SENSITIVE
occurredAt = ticket.createdAt
```

Audit does not store:

- Title
- Description
- JWT
- Idempotency Key
- Request body
- Secrets

The Audit record is append-only. A required Audit insert failure causes Create Ticket to fail closed and roll back.

Authentication and authorization failures may generate Security Audit outside the business transaction without exposing tokens.

---

# 19. Observability

## Trace

At minimum:

```text
HTTP POST /api/v1/tickets
CreateTicketUseCase
ticket.create
ticket.create.transaction
db.ticket.insert
db.resolution_cycle.insert
db.sla_cycle.insert
db.history.insert
db.audit.insert
db.outbox.insert
db.idempotency.complete
```

Allowed bounded attributes:

```text
service.name
operation
result
status
application_code
source
replayed
```

TicketId may be a controlled trace attribute but never a Prometheus label.

## Metrics

At minimum:

```text
opsmind_ticket_http_requests_total
opsmind_ticket_http_request_duration_seconds
opsmind_ticket_http_errors_total
opsmind_ticket_rate_limited_total
opsmind_ticket_authorization_denied_total
opsmind_ticket_idempotency_replay_total
opsmind_ticket_created_total
```

Bounded labels:

```text
route
method
status_class
operation
result
application_code
source
```

## Logging

Allowed:

```text
traceId
correlationId
operation
safe error code
result
duration
```

Never log the raw request body.

---

# 20. Success Response

```http
HTTP/1.1 201 Created
Location: /api/v1/tickets/{ticketId}
ETag: "0"
Content-Type: application/json
```

```json
{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "status": "NEW",
  "createdAt": "2026-07-23T16:30:00Z",
  "version": 0
}
```

The response body must be safe to persist in `idempotency_records.response_body`.

---

# 21. Error Contract

Envelope:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The request is invalid.",
    "traceId": "8f03...",
    "correlationId": "corr-...",
    "details": {}
  }
}
```

## Error Matrix

| Scenario | HTTP | Error Code |
|---|---:|---|
| Invalid JSON or field | 400 | `VALIDATION_ERROR` |
| Missing Idempotency Key | 400 | `VALIDATION_ERROR` |
| Missing, invalid, or expired JWT | 401 | `UNAUTHENTICATED` |
| Missing `tickets:create` | 403 | `FORBIDDEN` |
| Same key with different payload | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Same key still processing | 409 | `REQUEST_IN_PROGRESS` |
| Unsafe integrity conflict | 409 or 500 | `DATA_INTEGRITY_CONFLICT` |
| Rate limit exceeded | 429 | `RATE_LIMITED` |
| Local database unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected internal failure | 500 | `INTERNAL_ERROR` |

Errors never expose:

- Stack trace
- SQL
- Table name
- Constraint name
- Internal exception class
- Raw JWT
- Password
- Database connection string

---

# 22. Acceptance Scenarios

Executable scenarios are in:

```text
acceptance.feature
```

Minimum coverage:

1. Valid Employee creates one Ticket.
2. Initial Ticket, Resolution Cycle, SLA Cycle, and History are correct.
3. Same key and same payload returns the original result.
4. Same key and different payload is rejected.
5. Fresh `IN_PROGRESS` returns `REQUEST_IN_PROGRESS`.
6. RequesterId injection is rejected.
7. Missing scope is rejected.
8. Missing Idempotency Key is rejected.
9. SLA insert failure rolls back everything.
10. Audit insert failure rolls back everything.
11. Outbox insert failure rolls back everything.
12. Concurrent duplicate requests create one Ticket.
13. The event excludes Description, requester email, and secrets.

---

# 23. Tests First

## Domain RED

```text
TicketCreationTest
TicketTitleTest
TicketDescriptionTest
ApplicationCodeTest
TicketCreatedDomainEventTest
```

## Application RED

```text
CreateTicketApplicationServiceTest
CreateTicketAuthorizationTest
CreateTicketIdempotencyReplayTest
```

## API RED

```text
CreateTicketControllerTest
CreateTicketValidationTest
CreateTicketSecurityTest
CreateTicketMassAssignmentTest
CreateTicketErrorContractTest
```

## Persistence RED

```text
FlywayCreateTicketMigrationIT
CreateTicketPersistenceIT
CreateInitialResolutionCycleIT
CreateInitialSlaCycleIT
TicketCreationConstraintIT
```

## Transaction RED

```text
CreateTicketAtomicityIT
```

Failure injection:

```text
FAIL_TICKET_INSERT
FAIL_RESOLUTION_CYCLE_INSERT
FAIL_SLA_CYCLE_INSERT
FAIL_HISTORY_INSERT
FAIL_AUDIT_INSERT
FAIL_OUTBOX_INSERT
FAIL_IDEMPOTENCY_COMPLETION
```

## Idempotency and Concurrency RED

```text
CreateTicketIdempotencyIT
CreateTicketConcurrentIdempotencyIT
CreateTicketStaleIdempotencyIT
```

## Contract and Privacy RED

```text
TicketCreatedEventContractTest
TicketCreatedEventRedactionTest
CreateTicketAuditRedactionTest
```

## Architecture and Telemetry

```text
LayerDependencyTest
CreateTicketTelemetryTest
```

---

# 24. Package and Class Mapping

Recommended:

```text
ticket.api.publicapi
├── PublicTicketController
├── CreateTicketRequest
├── CreateTicketResponse
└── PublicTicketApiMapper

ticket.application.port.in
└── CreateTicketUseCase

ticket.application.command
├── CreateTicketCommand
└── CreateTicketResult

ticket.application.service
└── CreateTicketApplicationService

ticket.application.port.out
├── TicketRepository
├── TicketResolutionCycleRepository
├── TicketSlaRepository
├── TicketHistoryWriter
├── AuditRecordPort
├── OutboxEventRepository
├── IdempotencyRepository
├── TicketIdGenerator
├── TicketDisplayIdGenerator
├── ClockPort
└── SlaPolicyResolver

ticket.domain
├── Ticket
├── TicketId
├── TicketDisplayId
├── RequesterId
├── TicketTitle
├── TicketDescription
├── ApplicationCode
├── TicketSource
├── TicketStatus
├── TicketPriority
└── TicketCreated

ticket.infrastructure.persistence
├── TicketJpaEntity
├── TicketResolutionCycleJpaEntity
├── TicketSlaCycleJpaEntity
├── TicketStatusHistoryJpaEntity
├── AuditRecordJpaEntity
├── OutboxEventJpaEntity
├── IdempotencyRecordJpaEntity
├── TicketPersistenceMapper
└── Persistence Adapters
```

The Application Service depends on ports, never Spring Data repository implementations.

---

# 25. Traceability

The planned traceability entry is in:

```text
traceability-entry.yaml
```

After implementation, merge it into:

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

Planned class and test names must be updated to match actual code.

---

# 26. Non-functional Requirements

## Reliability

- All required local records commit atomically.
- API success does not require RabbitMQ availability.
- Display-ID collision retries are bounded.
- Idempotent concurrency never creates duplicate resources.

## Performance

Phase 01 target:

```text
Create command p95 < 800 ms
Create command p99 < 2 s
```

The target excludes external network calls and broker confirms.

## Availability

Recommended API SLO:

```text
99.5%
```

## Data Protection

- Title and Description are SENSITIVE.
- SECRET data is rejected from Domain, Audit, Event, logs, and traces.
- The integration event minimizes PII.

---

# 27. Definition of Done

`SPEC-TW-001` is complete only when:

- [ ] The Spec is reviewed and frozen.
- [ ] Phase 00 is complete.
- [ ] API-001 contract passes.
- [ ] SM-001 passes.
- [ ] BI-001–008 pass.
- [ ] Initial Resolution Cycle is correct.
- [ ] Initial SLA Cycle is correct.
- [ ] Initial Status History is correct.
- [ ] Local Business Audit is correct.
- [ ] `ticket.created.v1` Outbox record is correct.
- [ ] Idempotent replay returns a stable result.
- [ ] Same key with different payload is rejected.
- [ ] 100 concurrent duplicates create one Ticket.
- [ ] Any required insert failure rolls back all records.
- [ ] RequesterId comes only from JWT.
- [ ] Mass assignment is blocked.
- [ ] Event, Audit, and log redaction tests pass.
- [ ] Domain has no Spring or JPA dependency.
- [ ] PostgreSQL Testcontainer tests pass.
- [ ] ArchUnit passes.
- [ ] `./mvnw clean verify` passes.
- [ ] CI passes.
- [ ] Traceability is updated.
- [ ] The README curl example works.

---

# 28. Business Guarantee After Completion

After this specification is implemented, OpsMind guarantees:

```text
One valid Employee Ticket-creation intent,
despite duplicate clicks, network retries, concurrent requests,
or partial database failures,
creates only one complete, auditable, workflow-ready Ticket.
```
