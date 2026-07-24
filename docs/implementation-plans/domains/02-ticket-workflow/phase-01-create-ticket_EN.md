# OpsMind Ticket Workflow — Phase 01 Create Ticket Vertical Slice

> **Document ID:** IMP-TW-P01  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 01  
> **Phase Name:** Create Ticket Vertical Slice  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Prerequisite:** Phase 00 Engineering Foundation exit criteria passed  
> **Primary Feature Spec:** `SPEC-TW-001-create-ticket`  
> **Code Directory:** `services/ticket-workflow-service/`  
> **Spec Directory:** `docs/specs/domains/02-ticket-workflow/SPEC-TW-001-create-ticket/`  
> **Traceability:** `docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml`

---

# 1. Objective

Phase 01 delivers the first complete Ticket Workflow business vertical slice:

```text
Authenticated Employee
→ POST /api/v1/tickets
→ Authentication / Authorization
→ Idempotency
→ CreateTicketApplicationService
→ Ticket.create()
→ PostgreSQL
→ Initial Status History
→ Required Audit
→ Transactional Outbox
→ HTTP 201 Response
```

At the end of the phase, the service can:

- Accept an Employee IT-support Ticket request.
- Obtain requester identity from the authenticated principal rather than the request body.
- Create a Ticket with initial status `NEW`.
- Commit the Ticket, initial status history, audit, Outbox record, and idempotency result in one local database transaction.
- Return a stable API response, Location header, and ETag.
- Remain correct under replay, partial failure, and concurrent requests.

---

# 2. Why Create Ticket Is Implemented First

Create Ticket is the entry point to the complete Ticket lifecycle. Without it, the platform cannot reliably implement:

- Triage
- Agent workflows
- Waiting for user
- Approval
- Tool execution
- Verification
- Resolution
- Reopen
- Escalation

This phase also provides the first real validation of:

```text
Domain Aggregate
API Contract
Application Service
Persistence Adapter
Flyway Migration
Transaction Boundary
API Idempotency
Audit
Transactional Outbox
Security
Observability
Testing Strategy
```

As the smallest complete vertical slice, it exposes early whether:

- The Domain Model is overdesigned or missing key types.
- Package dependencies follow the hexagonal direction.
- API DTOs, Domain objects, and JPA entities remain separate.
- Transactional Outbox and idempotency are practical.
- Security context propagates requester identity correctly.
- Unit, integration, and contract tests work together.

---

# 3. Preconditions

Before Phase 01 begins:

- Phase 00 Exit Review passes.
- `./mvnw clean verify` passes.
- PostgreSQL Testcontainer works.
- RabbitMQ Testcontainer works.
- ArchUnit executes.
- Spring Security default deny exists.
- Actuator health is available.
- The Docker image builds and starts.
- CI executes.
- Create Ticket business code has not been implemented early.

---

# 4. Design References

Phase 01 uses the approved Ticket Workflow LLD as its design baseline.

## `01-domain-model`

For:

- Ticket aggregate
- TicketId
- DisplayTicketId
- RequesterId
- TicketTitle
- TicketDescription
- TicketStatus
- TicketSource
- CreatedAt
- Aggregate version

## `02-business-invariants`

The Feature Spec must list the exact invariant IDs governing:

- Requester identity
- Initial state
- Server-generated identity
- Initial history
- Audit
- Outbox
- Idempotency
- Secret redaction

## `03-state-machine`

References:

```text
Initial → NEW
```

The client cannot select a different initial status.

## `04-use-cases`

References:

```text
UC-01 Create Ticket
```

## `05-api-contracts`

References:

```text
POST /api/v1/tickets
```

including request DTO, response DTO, error envelope, `Idempotency-Key`, authentication, authorization, validation, and HTTP status.

## `06-event-contracts`

References:

```text
ticket.created.v1
```

Phase 01 must persist it to the Outbox. Whether a RabbitMQ publisher is included in this phase is decided in Section 8.

## `07-data-model`

At minimum:

```text
ticket.tickets
ticket.ticket_status_history
ticket.audit_records
ticket.outbox_events
ticket.idempotency_records
```

Final names follow the approved LLD.

## `08-transaction-and-outbox`

Commits:

```text
Ticket
+ Status History
+ Audit
+ Outbox
+ Idempotency Completion
```

in one local database transaction.

## `09-concurrency-and-idempotency`

Implements:

- `actor_scope + idempotency_key`
- Canonical request hash
- Same key / same payload replay
- Same key / different payload conflict
- In-progress handling
- Initial optimistic versioning

## `10-error-handling-and-reconciliation`

Implements Phase 01 validation, authentication, authorization, idempotency, database, and unexpected-error mapping.

## `11-security-and-authorization`

Implements:

- Employee JWT
- `tickets:create` scope
- RequesterId from security context
- Mass-assignment protection
- No requester identity injection

## `12-observability-and-audit`

Implements:

- HTTP trace
- Application span
- Structured logging
- Create counter
- Duration metric
- Required audit
- PII and secret redaction

## `13-package-and-class-design`

Implements the API adapter, Application Service, Domain aggregate, outbound ports, persistence adapter, audit adapter, Outbox adapter, and security adapter.

## `14-testing-strategy`

Implements Domain, Application, Controller, Security, PostgreSQL, Atomicity, Idempotency, Event Contract, Architecture, and Observability tests.

---

# 5. Feature Spec

The business source of truth for this phase is:

```text
SPEC-TW-001-create-ticket
```

Recommended structure:

```text
docs/specs/domains/02-ticket-workflow/
└── SPEC-TW-001-create-ticket/
    ├── spec_CN.md
    ├── spec_EN.md
    ├── acceptance.feature
    └── examples/
        ├── valid-request.json
        ├── valid-response.json
        ├── invalid-request.json
        └── error-response.json
```

Business coding begins only after the Spec review.

---

# 6. Scope

Phase 01 includes:

- Create Ticket command
- Initial `NEW` state
- Server-generated TicketId and display ID
- Requester identity from JWT
- Request validation
- Authorization
- API idempotency
- Ticket persistence
- Initial status history
- Required audit record
- `ticket.created.v1` Outbox record
- HTTP response and error envelope
- Tracing, logging, and metrics
- Unit, Application, Integration, Contract, Security, and Atomicity tests

---

# 7. Non-goals

Phase 01 does not implement:

- Ticket query or list
- Add message
- Triage or classification
- Agent workflow
- Waiting for user
- Approval
- Tool execution
- Verification
- Resolve, close, or reopen
- Cancel, assign, or escalate
- RabbitMQ consumer set
- Reconciliation workflow
- Full Keycloak realm hardening
- Dashboard and alert suite

It also does not introduce future business methods without an approved Spec and tests.

---

# 8. Vertical Slice Boundary

Entry point:

```text
POST /api/v1/tickets
```

Mandatory business boundary:

```text
Committed Ticket
+
Committed Initial Status History
+
Committed Required Audit
+
Committed Outbox Record
+
Committed Idempotency Response
```

The recommended plan is to complete **Outbox persistence** in Phase 01 and complete the reusable Outbox Publisher before Phase 03, when real cross-service event flow is first needed.

Therefore Phase 01 requires:

- A real Outbox row
- Event contract validation
- Transaction atomicity validation
- EventId, aggregate version, and payload validation

Phase 01 does not require:

- Publisher confirms
- Retry queues
- DLQ
- RabbitMQ consumers

Those capabilities must be ready before the first real asynchronous integration.

---

# 9. API Behavior

## 9.1 Request

Example:

```json
{
  "title": "Unable to connect to VPN",
  "description": "The VPN client shows authentication failed.",
  "categoryHint": "NETWORK_ACCESS",
  "source": "EMPLOYEE_PORTAL"
}
```

The client cannot provide:

- `ticketId`
- `displayId`
- `requesterId`
- `status`
- `priority`
- `assignedTeam`
- `assignedAgent`
- `workflowId`
- `approvalId`
- `createdAt`
- `version`

## 9.2 Headers

```text
Authorization: Bearer <JWT>
Idempotency-Key: <stable-client-generated-key>
Content-Type: application/json
```

## 9.3 Success Response

Recommended:

```text
HTTP 201 Created
Location: /api/v1/tickets/{ticketId}
ETag: "<version>"
```

The response contains only fields visible to the Employee and needed for the next interaction.

## 9.4 Error Response

Use the approved error envelope.

At minimum cover:

- Invalid body
- Missing or invalid token
- Missing scope
- Missing Idempotency Key
- Same key with different payload
- Request in progress
- Database failure
- Unexpected internal failure

Never expose stack traces, SQL, table names, raw JWTs, secrets, or internal exception classes.

---

# 10. Domain Behavior

Recommended factory:

```java
Ticket.create(
    TicketId ticketId,
    DisplayTicketId displayId,
    RequesterId requesterId,
    TicketTitle title,
    TicketDescription description,
    TicketSource source,
    Instant now
)
```

After creation:

```text
status = NEW
version = initial aggregate version
createdAt = now
updatedAt = now
requesterId = authenticated requester
activeWorkflow = none
pendingAction = none
resolution = none
```

The Domain emits:

```text
TicketCreatedDomainEvent
```

The Application Layer maps it to:

```text
ticket.created.v1
```

---

# 11. Business Invariants

The Feature Spec must list exact BI IDs. At minimum, Phase 01 guarantees:

- RequesterId comes from trusted security context.
- Initial status is always `NEW`.
- TicketId and display ID are generated by the server.
- No active workflow, pending action, or resolution exists at creation.
- Aggregate version is initialized correctly.
- Successful creation produces initial status history.
- The integration event is stored in the Outbox.
- One Idempotency Key does not create multiple Tickets.
- Same key with different payload is rejected.
- Required audit is stored.
- Secrets do not enter Domain Events, Outbox, logs, or traces.

---

# 12. Transaction Boundary

Recommended Application Service:

```text
CreateTicketApplicationService
```

Its public method uses:

```text
@Transactional
```

Transaction sequence:

```text
1. Validate command
2. Authorize actor
3. Reserve or read Idempotency Key
4. Generate IDs
5. Ticket.create()
6. Persist Ticket
7. Persist Initial Status History
8. Persist Required Audit Record
9. Persist ticket.created.v1 Outbox Record
10. Complete Idempotency Record
11. Commit
```

Any required database-step failure causes:

```text
ROLLBACK ALL
```

Do not perform inside the transaction:

- RabbitMQ publish
- Agent Runtime call
- Approval Service call
- Tool Gateway call
- External HTTP call
- Waiting for Publisher Confirm

---

# 13. Idempotency

Scope:

```text
actor_scope + idempotency_key
```

Request hash:

```text
Canonical JSON → SHA-256
```

Behavior:

```text
same key + same payload + completed
→ return stored response

same key + different payload
→ IDEMPOTENCY_KEY_REUSED

same key + fresh in-progress
→ REQUEST_IN_PROGRESS

stale in-progress + committed Ticket
→ rebuild or recover response

stale in-progress + no committed Ticket
→ retryable recovery path
```

Concurrency goal:

```text
100 concurrent identical requests
→ exactly one Ticket
```

---

# 14. Persistence

At minimum create:

```text
TicketJpaEntity
TicketStatusHistoryJpaEntity
AuditRecordJpaEntity
OutboxEventJpaEntity
IdempotencyRecordJpaEntity
```

and:

```text
TicketPersistenceMapper
TicketPersistenceAdapter
StatusHistoryPersistenceAdapter
AuditPersistenceAdapter
OutboxPersistenceAdapter
IdempotencyPersistenceAdapter
```

Constraints:

- Domain objects and JPA entities remain separate.
- Controllers do not use JPA entities.
- Domain classes do not use JPA annotations.
- Application services do not depend directly on Spring Data repositories.
- Explicit ports are used instead of a universal generic repository.

---

# 15. Flyway Migrations

Recommended:

```text
V001__create_ticket_schema.sql
V002__create_ticket_table.sql
V003__create_ticket_status_history.sql
V004__create_audit_records.sql
V005__create_outbox_events.sql
V006__create_idempotency_records.sql
```

A slice-level combined migration is also acceptable when it remains reviewable and does not create many future tables early.

Verify:

- Primary keys
- Unique constraints
- Check constraints
- Foreign keys
- TIMESTAMPTZ
- Aggregate version
- Request hash
- Outbox EventId
- Append-only audit foundation

---

# 16. Outbox Event

Phase 01 event:

```text
ticket.created.v1
```

The envelope follows `06-event-contracts`.

The payload is minimal and should contain:

- TicketId
- Display ID
- Pseudonymous requester reference
- Source
- Initial status
- CreatedAt
- Aggregate version
- CorrelationId

It must not contain the full description, raw JWT, authorization header, or secrets.

Verify:

- JSON Schema Draft 2020-12
- Event version
- Routing key
- Producer identity
- Data classification
- Aggregate version
- No secrets

---

# 17. Security

## Authentication

Require a valid Employee JWT and validate signature, issuer, audience, expiration, not-before, and required client or scope.

## Authorization

Require:

```text
tickets:create
```

or the approved equivalent.

## Requester Identity

RequesterId comes only from:

```text
SecurityPrincipal
```

not from the request DTO.

## Mass Assignment

The DTO explicitly defines allowed fields, and unknown-field behavior follows the API contract.

## Rate Limit

At minimum reserve per-user create limits, burst protection, and an abuse metric. Full enforcement may be hardened in Phase 09.

---

# 18. Audit

Successful creation records:

```text
action = TICKET_CREATED
actor
actorType
ticketId
commandId
traceId
clientId
scope
result = SUCCESS
occurredAt
```

Security failures may record authentication failure, authorization failure, idempotency abuse, and suspicious payload.

Audit metadata does not store the complete description.

If a required audit insert fails:

```text
Create Ticket fails closed
```

---

# 19. Observability

## Trace

Recommended spans:

```text
POST /api/v1/tickets
CreateTicketUseCase
Ticket.create
ticket.transaction
db.ticket.insert
db.history.insert
db.audit.insert
db.outbox.insert
db.idempotency.complete
```

## Log

Record TraceId, CorrelationId, CommandId, safe error code, result, and duration.

Do not record raw descriptions, JWTs, secrets, requester email, or database passwords.

## Metrics

Recommended:

```text
opsmind_ticket_create_total
opsmind_ticket_create_duration_seconds
opsmind_ticket_create_failure_total
opsmind_ticket_idempotency_replay_total
opsmind_ticket_idempotency_conflict_total
```

Do not use ticketId, requesterId, eventId, or idempotencyKey as labels.

---

# 20. TDD Execution Order

```text
SPEC → RED → GREEN → REFACTOR → VERIFY
```

## Step 1 — Spec Review

Freeze input, output, state, transaction, event, errors, security, and acceptance scenarios.

## Step 2 — Domain RED

Write first:

```text
TicketCreationTest
TicketValueObjectTest
TicketCreatedDomainEventTest
```

## Step 3 — Domain GREEN

Implement the minimum:

```text
Ticket
TicketId
DisplayTicketId
RequesterId
TicketTitle
TicketDescription
TicketStatus
TicketSource
TicketCreatedDomainEvent
```

## Step 4 — Application RED

Write:

```text
CreateTicketApplicationServiceTest
```

Verify authorization, idempotency, ID generation, history, audit, Outbox, and result.

## Step 5 — Application GREEN

Implement:

```text
CreateTicketUseCase
CreateTicketCommand
CreateTicketResult
CreateTicketApplicationService
```

and required ports.

## Step 6 — Persistence RED

Write:

```text
CreateTicketPersistenceIT
CreateTicketAtomicityIT
CreateTicketIdempotencyIT
```

## Step 7 — Persistence GREEN

Implement Flyway, JPA entities, mappers, and adapters.

## Step 8 — API RED

Write:

```text
CreateTicketControllerTest
CreateTicketSecurityTest
CreateTicketApiContractTest
```

## Step 9 — API GREEN

Implement:

```text
PublicTicketController
CreateTicketRequest
CreateTicketResponse
CreateTicketDtoMapper
```

## Step 10 — Event Contract RED

Write:

```text
TicketCreatedEventContractTest
```

## Step 11 — Event GREEN

Implement Domain Event to Integration Event to Outbox JSON.

## Step 12 — Refactor

Remove duplication, strengthen value objects, verify package direction, transaction boundaries, and secret redaction.

## Step 13 — Verify

Run Unit, Application, Controller, Security, PostgreSQL Integration, Atomicity, Idempotency, Event Contract, ArchUnit, and Coverage tests.

---

# 21. Test Inventory

## Domain

```text
TicketCreationTest
TicketValueObjectTest
TicketCreatedDomainEventTest
```

## Application

```text
CreateTicketApplicationServiceTest
```

## API

```text
CreateTicketControllerTest
CreateTicketValidationTest
CreateTicketSecurityTest
CreateTicketErrorContractTest
```

## Persistence

```text
CreateTicketPersistenceIT
TicketCreationConstraintIT
FlywayTicketCreationMigrationIT
```

## Transaction

```text
CreateTicketAtomicityIT
```

Covers Ticket, history, audit, Outbox, and idempotency-completion failures.

## Idempotency

```text
CreateTicketIdempotencyIT
CreateTicketConcurrentIdempotencyIT
```

## Event

```text
TicketCreatedEventContractTest
TicketCreatedEventRedactionTest
```

## Architecture and Observability

```text
LayerDependencyTest
CreateTicketTelemetryTest
```

---

# 22. Acceptance Scenarios

```gherkin
Feature: Create Ticket

  Scenario: Create a valid Ticket
    Given an authenticated employee with tickets:create scope
    And a unique Idempotency-Key
    When the employee submits a valid Ticket request
    Then the response status is 201
    And exactly one Ticket is created
    And the Ticket status is NEW
    And one initial status history record exists
    And one required audit record exists
    And one ticket.created.v1 Outbox record exists

  Scenario: Replay the same request
    Given a completed Create Ticket request
    When the same actor repeats the same payload with the same Idempotency-Key
    Then the stored response is returned
    And no second Ticket is created

  Scenario: Reuse the key with a different payload
    Given a completed Create Ticket request
    When the same actor submits a different payload with the same Idempotency-Key
    Then the request is rejected
    And the error code is IDEMPOTENCY_KEY_REUSED

  Scenario: Reject requester identity injection
    Given an authenticated employee
    When the request body includes a requesterId
    Then the request is rejected or the field is disallowed by contract
    And the authenticated actor remains the only requester identity source

  Scenario: Roll back when Outbox insert fails
    Given a valid Create Ticket request
    And the Outbox insert fails
    When the command is executed
    Then no Ticket remains committed
    And no status history remains committed
    And no successful idempotency response is stored
```

---

# 23. Implementation Tasks

```text
P01-T01 Review SPEC-TW-001
P01-T02 Add Domain RED Tests
P01-T03 Implement Domain Creation
P01-T04 Add Application RED Tests
P01-T05 Implement Application Service and Ports
P01-T06 Add Flyway Migrations
P01-T07 Implement Persistence Adapters
P01-T08 Add Persistence and Atomicity Tests
P01-T09 Add API and Security Tests
P01-T10 Implement Create Ticket API
P01-T11 Add Event Contract Test
P01-T12 Implement Outbox Event Mapping
P01-T13 Add Telemetry and Redaction
P01-T14 Update Traceability
P01-T15 Update Service README
```

---

# 24. Recommended Pull Request Split

## PR 1 — Spec and Domain

```text
docs(spec): define SPEC-TW-001 create ticket
test(ticket): add failing ticket creation domain tests
feat(ticket): implement ticket creation domain model
```

## PR 2 — Persistence and Transaction

```text
test(persistence): add create ticket integration tests
feat(database): add ticket creation migrations
feat(persistence): implement ticket creation adapters
test(transaction): verify create ticket atomicity
```

## PR 3 — API, Security, and Contract

```text
test(api): add create ticket controller and security tests
feat(api): implement create ticket endpoint
test(contract): add ticket.created.v1 contract tests
feat(outbox): persist ticket.created.v1
```

## PR 4 — Hardening

```text
feat(observability): add create ticket telemetry
test(idempotency): add concurrent replay tests
docs(traceability): complete SPEC-TW-001 mapping
```

---

# 25. Deliverables

Documentation:

```text
phase-01-create-ticket_CN.md
phase-01-create-ticket_EN.md
SPEC-TW-001-create-ticket/spec_CN.md
SPEC-TW-001-create-ticket/spec_EN.md
acceptance.feature
traceability-matrix.yaml
```

Code:

```text
Ticket Domain Creation
CreateTicketApplicationService
Create Ticket API
Flyway Migrations
Persistence Adapters
Audit
Outbox
Idempotency
Telemetry
```

Tests:

```text
Domain
Application
Controller
Security
Persistence
Atomicity
Idempotency
Event Contract
Architecture
Observability
```

---

# 26. Risks and Mitigations

## Scope Becomes Too Large

Implement only Create Ticket. Keep query and message behavior in Phase 02. Do not add RabbitMQ consumers.

## All Future Tables Are Created Early

Create only tables required by the current slice.

## Business Logic Enters the Controller

Controllers perform HTTP mapping only. Domain and Application layers own behavior.

## JPA Entities Become API DTOs

Keep API DTOs, Domain objects, and persistence entities separate.

## RabbitMQ Is Called Inside the Transaction

Persist the Outbox only. Publish outside the business transaction.

## Idempotency Is Deferred

Create Ticket must implement idempotency in Phase 01.

## Security Is Disabled for Tests

Mock JWT provides authentication context only. Authorization rules still execute.

## Outbox Payload Contains Excessive PII

Use a minimal payload and pseudonymous requester reference, enforced by redaction tests.

---

# 27. Exit Criteria

## Spec

- `SPEC-TW-001-create-ticket` is reviewed.
- Acceptance scenarios are frozen.
- Design references are complete.

## Domain

- Ticket creation unit tests pass.
- Initial status is always `NEW`.
- Domain has no Spring or JPA dependency.
- Value-object validation passes.

## API

- `POST /api/v1/tickets` returns `201`.
- Request DTO prevents mass assignment.
- Error envelope matches the contract.
- `Idempotency-Key` is required.
- Location and ETag are correct.

## Security

- Only a valid Employee JWT can create.
- Missing scope is rejected.
- RequesterId comes only from security context.
- JWTs and secrets are not exposed.

## Persistence

- Flyway succeeds from an empty database.
- Real PostgreSQL is used.
- Constraint tests pass.
- H2 is not used.

## Transaction

- Ticket, history, audit, Outbox, and idempotency commit atomically.
- Failure in any required step rolls everything back.

## Idempotency

- Same key and same payload replays.
- Same key and different payload conflicts.
- Concurrent duplicates create one Ticket.

## Event

- `ticket.created.v1` schema passes.
- Outbox EventId is unique.
- Payload contains no secret.
- Aggregate version is correct.

## Observability

- TraceId correlates API and transaction work.
- Metrics increment correctly.
- Logs contain no sensitive data.
- Audit is complete.

## Quality

```text
./mvnw clean verify
```

passes, including ArchUnit, coverage gates, secret scan, CI, and Docker startup.

## Documentation

- Service README is updated.
- Curl example works.
- Traceability matrix is updated.
- Phase 02 boundaries are clear.

---

# 28. Exit Review Checklist

- [ ] Phase 00 is complete.
- [ ] SPEC-TW-001 is reviewed.
- [ ] Domain RED tests were created first.
- [ ] Application RED tests were created first.
- [ ] API and Integration RED tests were created first.
- [ ] Ticket initial status is fixed to `NEW`.
- [ ] RequesterId comes from JWT.
- [ ] `Idempotency-Key` is implemented.
- [ ] Ticket, history, audit, Outbox, and idempotency commit atomically.
- [ ] `ticket.created.v1` contract passes.
- [ ] PostgreSQL Testcontainer passes.
- [ ] Concurrent duplicate test passes.
- [ ] Security tests pass.
- [ ] Telemetry redaction tests pass.
- [ ] ArchUnit passes.
- [ ] `./mvnw clean verify` passes.
- [ ] CI passes.
- [ ] Traceability is updated.
- [ ] README is updated.

---

# 29. What Phase 01 Enables

After Exit Review, proceed to:

```text
Phase 02 — Ticket Query and Message Slice
```

Next Feature Specs:

```text
SPEC-TW-002-get-ticket
SPEC-TW-003-list-requester-tickets
SPEC-TW-004-add-ticket-message
SPEC-TW-005-support-queue-query
SPEC-TW-006-ticket-timeline
```

Phase 02 reuses the Ticket identity, persistence foundation, security principal, error envelope, trace context, audit foundation, API versioning, Testcontainers, and CI quality gates established in Phase 01.

---

# 30. Definition of Done

Phase 01 is complete when:

```text
Ticket Workflow has its first complete business vertical slice,
driven by a reviewed Feature Spec, implemented through TDD,
and protected by transaction, idempotency, security, audit,
Outbox, observability, and automated tests.
```
