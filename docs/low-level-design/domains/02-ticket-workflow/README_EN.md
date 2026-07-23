# OpsMind — 02 Ticket Workflow Detailed Design

> **Domain:** Ticket & Business Workflow  
> **Phase:** Low-Level Design  
> **Version:** 1.0  
> **Status:** Draft for Domain Design  
> **Dependency:** `technology-baseline`  
> **Recommended Path:** `docs/low-level-design/domains/02-ticket-workflow/README_EN.md`

---

## 1. Purpose

This document is the main design entry point for the OpsMind Ticket Workflow domain.

It defines:

- The domain's responsibilities in the MVP
- The relationship between Ticket, Agent Workflow, Approval, Tool Execution, and Verification
- The ticket lifecycle
- Data ownership, API, and event boundaries
- Transactional outbox, concurrency, and idempotency principles
- Security, observability, and testing expectations
- The sequence of lower-level design documents required before implementation

This README is a domain navigation and boundary document. It does not replace the detailed domain model, state machine, contracts, data model, and code-level design.

---

## 2. Position in the Golden Path

```text
Employee submits login issue
→ Ticket is created
→ Ticket enters TRIAGING
→ Agent Runtime starts investigation
→ Ticket enters INVESTIGATING
→ Wait for user or approval
→ Approved tool action executes
→ Ticket enters EXECUTING
→ Verification begins
→ Ticket enters VERIFYING
→ Verification succeeds
→ Ticket enters RESOLVED
→ User confirms or timeout closes the ticket
→ Ticket enters CLOSED
```

Ticket Workflow is the business backbone:

```text
User Access
→ creates and displays tickets

Agent Runtime
→ investigates tickets

Policy & Approval
→ authorizes sensitive actions

Tool Gateway
→ executes enterprise operations

Verification Agent
→ determines whether the ticket may resolve

Memory
→ learns from resolved tickets

Evaluation
→ evaluates ticket-handling quality

Observability
→ traces tickets across services
```

---

## 3. Domain Goals

Ticket Workflow must guarantee:

1. Every user issue has a unique ticket.
2. Ticket status changes only through legal transitions.
3. Every status transition is historically recorded.
4. Ticket state and Agent Workflow state remain separate.
5. Duplicate events do not duplicate business effects.
6. Concurrent updates do not silently overwrite each other.
7. Tool success is not equivalent to ticket resolution.
8. Verification succeeds before `RESOLVED`.
9. Cancellation blocks new privileged actions.
10. Business state and outbox events are stored atomically.
11. All critical changes are auditable, traceable, and recoverable.

---

## 4. Responsibilities

Ticket Workflow owns:

- Create, retrieve, and update tickets
- Ticket state-machine enforcement
- User messages
- Ticket status history
- Assignment and basic SLA tracking
- Cancel, escalate, resolve, close, and reopen behavior
- Active Workflow association
- Consumption of cross-domain events
- Publication of ticket domain events
- Transactional outbox
- Optimistic locking
- API idempotency
- Event idempotency
- Ticket timeline
- Employee, support, administrator, and auditor APIs

## 4.1 Non-responsibilities

Ticket Workflow does not own:

- LLM calls
- Agent selection or reasoning
- Okta, Duo, or enterprise-system queries
- Tool execution
- Tool risk classification
- Approval decisions
- Enterprise credentials
- Long-term memory
- LangSmith agent traces
- Agent evaluation
- Infrastructure monitoring

---

## 5. Cross-Domain Boundaries

### User Access & Authentication

Owns login, tokens, roles, and frontend routing. Ticket Workflow owns ticket business data.

### Agent Runtime

Owns workflows, tasks, checkpoints, pause/resume, and investigation. It must not mutate `ticket.*`.

### Policy & Approval

Owns risk classification, approval requests, and decisions. Ticket Workflow applies approval events.

### Tool Gateway

Owns credentials, execution, and tool idempotency. Ticket Workflow applies tool results.

### Memory & Knowledge

Owns working memory, long-term memory, and RAG. Ticket Workflow publishes lifecycle events.

### Evaluation

Owns datasets, experiments, and quality evaluation. Ticket Workflow provides final business outcomes.

### Observability

Ticket Workflow uses OpenTelemetry and does not directly depend on LangSmith. Agent Runtime correlates LangSmith through `ticket_id`, `workflow_id`, and `trace_id`.

---

## 6. Core Business Objects

Complete definitions belong in `01-domain-model/`.

### Aggregate Root

```text
Ticket
```

### Candidate Entities

```text
Ticket
TicketMessage
TicketAssignment
SLARecord
TicketStatusHistory
```

### Candidate Value Objects

```text
TicketId
RequesterId
TicketTitle
TicketDescription
TicketCategory
TicketSubcategory
TicketPriority
TicketStatus
ApplicationCode
AssignmentTeam
WorkflowId
ApprovalId
ResolutionSummary
```

### Candidate Domain Events

```text
TicketCreated
TicketClassified
TicketStatusChanged
TicketWaitingForUser
TicketWaitingForApproval
TicketExecutionReady
TicketVerificationStarted
TicketResolved
TicketClosed
TicketCancelled
TicketReopened
TicketEscalated
```

The aggregate boundary must be finalized in `01-domain-model/`.

---

## 7. Ticket State Overview

Detailed design belongs in `03-state-machine/`.

### Main States

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
EXECUTING
VERIFYING
RESOLVED
CLOSED
```

### Exception and Recovery States

```text
ESCALATED
FAILED
CANCELLED
REOPENED
```

### Golden Path

```text
NEW
→ TRIAGING
→ INVESTIGATING
→ WAITING_FOR_APPROVAL
→ EXECUTING
→ VERIFYING
→ RESOLVED
→ CLOSED
```

### Common Branches

```text
INVESTIGATING
→ WAITING_FOR_USER
→ INVESTIGATING
```

```text
VERIFYING
→ INVESTIGATING
```

```text
RESOLVED
→ REOPENED
→ INVESTIGATING
```

```text
ANY_ACTIVE_STATE
→ ESCALATED
```

---

## 8. Ticket State vs. Workflow State

| Ticket Status | Workflow Status | Meaning |
|---|---|---|
| TRIAGING | RUNNING | Triage is active |
| INVESTIGATING | RUNNING | Agents are investigating |
| WAITING_FOR_USER | PAUSED | Waiting for user input |
| WAITING_FOR_APPROVAL | PAUSED | Waiting for approval |
| EXECUTING | RUNNING | Tool execution is active |
| VERIFYING | RUNNING | Verification is active |
| RESOLVED | COMPLETED | Business and workflow completed |
| ESCALATED | FAILED / PAUSED | Human support owns the case |
| CANCELLED | CANCELLED | Flow terminated |

Rules:

- Ticket Service does not substitute Workflow state for Ticket state.
- Agent Runtime does not directly set Ticket state.
- They collaborate through APIs and events.
- Eventual consistency is protected by business invariants.

---

## 9. MVP Use Cases

Detailed design belongs in `04-use-cases/`.

```text
UC-01 Create Ticket
UC-02 Get Ticket
UC-03 List Requester Tickets
UC-04 List Support Queue Tickets
UC-05 Add Ticket Message
UC-06 Classify Ticket
UC-07 Request More Information
UC-08 Receive User Reply
UC-09 Associate Active Workflow
UC-10 Request Approval
UC-11 Handle Approval Granted
UC-12 Handle Approval Rejected
UC-13 Handle Approval Expired
UC-14 Handle Tool Execution Completed
UC-15 Handle Tool Execution Failed
UC-16 Start Verification
UC-17 Handle Verification Success
UC-18 Handle Verification Failure
UC-19 Resolve Ticket
UC-20 Close Ticket
UC-21 Reopen Ticket
UC-22 Cancel Ticket
UC-23 Escalate Ticket
UC-24 Retrieve Ticket Timeline
```

---

## 10. API Overview

Detailed contracts belong in `05-api-contracts/`.

### Public APIs

```http
POST /api/v1/tickets
GET  /api/v1/tickets/{ticketId}
GET  /api/v1/tickets
POST /api/v1/tickets/{ticketId}/messages
POST /api/v1/tickets/{ticketId}/cancel
POST /api/v1/tickets/{ticketId}/reopen
POST /api/v1/tickets/{ticketId}/confirm-resolution
GET  /api/v1/tickets/{ticketId}/timeline
```

### Support APIs

```http
GET  /api/v1/support/tickets
POST /api/v1/support/tickets/{ticketId}/assign
POST /api/v1/support/tickets/{ticketId}/escalate
POST /api/v1/support/tickets/{ticketId}/close
```

### Internal APIs

```http
POST /internal/v1/tickets/{ticketId}/classify
POST /internal/v1/tickets/{ticketId}/transitions
POST /internal/v1/tickets/{ticketId}/workflows/{workflowId}/associate
GET  /internal/v1/tickets/{ticketId}/status
```

Internal APIs still require service identities.

---

## 11. Event Overview

Detailed contracts belong in `06-event-contracts/`.

### Published Events

```text
ticket.created
ticket.classified
ticket.status_changed
ticket.user_reply_requested
ticket.user_replied
ticket.approval_wait_started
ticket.execution_ready
ticket.verification_started
ticket.resolved
ticket.closed
ticket.cancelled
ticket.reopened
ticket.escalated
```

### Consumed Events

```text
agent.workflow.started
agent.workflow.failed
ticket.classification.completed
approval.requested
approval.granted
approval.rejected
approval.expired
tool.execution.completed
tool.execution.failed
verification.completed
```

### Event Envelope

```json
{
  "eventId": "evt-1001",
  "eventType": "ticket.created",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-23T16:30:00Z",
  "producer": "ticket-workflow-service",
  "traceId": "trace-abc",
  "correlationId": "INC-2048",
  "ticketId": "INC-2048",
  "workflowId": null,
  "aggregateId": "ticket-uuid",
  "aggregateVersion": 1,
  "payload": {}
}
```

---

## 12. Data Ownership

Detailed design belongs in `07-data-model/`.

```text
ticket.tickets
ticket.ticket_messages
ticket.ticket_status_history
ticket.ticket_assignments
ticket.sla_records
ticket.outbox_events
ticket.processed_events
ticket.idempotency_records
```

Rules:

- Only Ticket Workflow writes `ticket.*`.
- Other services never update ticket tables directly.
- Cross-service changes use APIs or events.
- Avoid foreign keys across service-owned schemas.
- Status history is append-only.
- Mutable aggregates use versions.
- Outbox events are written with business state in one transaction.

---

## 13. Transactions and Outbox

Detailed design belongs in `08-transaction-and-outbox/`.

### Create Ticket Transaction

```text
BEGIN
Insert Ticket
Insert Initial Status History
Insert ticket.created Outbox Event
Insert API Idempotency Record
COMMIT
```

### State Transition Transaction

```text
BEGIN
Load Ticket with expected version
Validate transition
Update Ticket
Insert Status History
Insert Outbox Event
COMMIT
```

Forbidden inside database transactions:

- RabbitMQ publication
- Agent Runtime calls
- LLM calls
- Tool Gateway calls
- LangSmith calls
- Enterprise API calls

Outbox Publisher:

```text
Read unpublished records
→ Publish to RabbitMQ
→ Confirm publish
→ Mark as published
```

---

## 14. Concurrency and Idempotency

Detailed design belongs in `09-concurrency-and-idempotency/`.

### Optimistic Locking

```sql
UPDATE ticket.tickets
SET status = :newStatus,
    version = version + 1,
    updated_at = :updatedAt
WHERE ticket_id = :ticketId
  AND version = :expectedVersion;
```

### API Idempotency

Applies to:

- Create Ticket
- Cancel Ticket
- Reopen Ticket
- Confirm Resolution

```http
Idempotency-Key: request-123
```

### Event Idempotency

```text
UNIQUE(consumer_name, event_id)
```

Events include `aggregateVersion` to detect duplicates, out-of-order delivery, and stale updates.

---

## 15. Initial Business Invariants

The complete list belongs in `02-business-invariants/`.

1. Every ticket has a requester.
2. Title and description are required.
3. A ticket has at most one active workflow.
4. `CANCELLED` cannot enter `EXECUTING`.
5. `CLOSED` cannot be automatically reopened by background events.
6. Verification must succeed before `RESOLVED`.
7. `WAITING_FOR_APPROVAL` requires an approval request.
8. Approval must match the ticket, workflow, and action.
9. Every transition writes status history.
10. Duplicate events cannot duplicate business changes.
11. Cancellation blocks new privileged actions.
12. Reopen requires a reason.
13. Category changes preserve history.
14. Tool success is not resolution success.
15. Verification failure returns to investigation or escalation.
16. Ticket Service never reads enterprise credentials.
17. Audit and status history are not overwritten.

---

## 16. Security Overview

Detailed design belongs in `11-security/`.

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
SERVICE_IDENTITY
```

Rules:

- Employees access their own tickets.
- Support accesses authorized queues.
- Administrators operate within authorized scope.
- Auditors are read-only.
- Internal APIs use service identities.
- Internal events validate source and schema.
- PII is minimized.
- Logs exclude tokens, credentials, and full sensitive descriptions.
- Ticket metadata exported to LangSmith is redacted.

---

## 17. Observability Overview

Detailed design belongs in `12-observability/`.

Ticket Workflow uses:

```text
OpenTelemetry
Prometheus
Structured JSON Logs
```

Ticket Service does not directly depend on LangSmith.

Trace context:

```text
trace_id
correlation_id
ticket_id
workflow_id
event_id
aggregate_version
requester_id_hash
```

Metrics:

```text
ticket_created_total
ticket_transition_total
ticket_transition_failed_total
ticket_cancelled_total
ticket_reopened_total
ticket_resolved_total
ticket_resolution_duration_seconds
ticket_waiting_for_user_duration_seconds
ticket_waiting_for_approval_duration_seconds
outbox_pending_count
outbox_publish_failure_total
duplicate_event_total
out_of_order_event_total
optimistic_lock_conflict_total
```

Agent Runtime writes ticket correlation fields into LangSmith metadata.

---

## 18. Failure Handling

Detailed design belongs in `10-failure-handling/`.

Required scenarios:

- Database failures
- Outbox insert failures
- RabbitMQ unavailability
- Outbox publication failures
- Duplicate events
- Out-of-order events
- Optimistic-lock conflicts
- Agent workflow failures
- Approval expiration or rejection
- Tool failure or unknown result
- Verification failure
- Cancellation during execution
- SSE delivery failure
- LangSmith unavailability
- OpenTelemetry export failure

Principles:

```text
PostgreSQL preserves business state
Cross-service state may be eventually consistent
Duplicate delivery must be safe
Telemetry failure never blocks business
Security failure rejects the operation
```

---

## 19. Testing Overview

Detailed design belongs in `14-testing-strategy/`.

### Unit Tests

- Ticket Aggregate
- Value Objects
- Business Invariants
- State Machine
- Domain Policies
- Error Mapping

### Integration Tests

- PostgreSQL
- Flyway
- Outbox
- RabbitMQ
- Idempotent Consumers
- Optimistic Locking
- Keycloak Authorization

### Contract Tests

- OpenAPI
- Event JSON Schema
- Error Envelope
- Internal APIs

### Failure Injection

- Stop RabbitMQ
- Duplicate, delay, or reorder events
- Crash the Outbox Publisher
- Cause optimistic-lock conflicts
- Expire approval
- Return verification failure

### End-to-End

```text
Create Ticket
→ Classify
→ Investigate
→ Request Approval
→ Approve
→ Execute Tool
→ Verify
→ Resolve
→ Close
```

---

## 20. Technology Baseline

```text
Language: Java 21
Framework: Spring Boot 3.5.x
Persistence: PostgreSQL + Spring Data JPA
Migration: Flyway
Messaging: RabbitMQ + Spring AMQP
Security: Spring Security + Keycloak JWT
Resilience: Resilience4j
Observability: OpenTelemetry
Testing: JUnit 5 + Mockito + Testcontainers + ArchUnit
Build: Maven Wrapper
```

---

## 21. Recommended Code Structure

```text
services/ticket-workflow-service/
├── pom.xml
├── Dockerfile
├── src/main/java/com/opsmind/ticket/
│   ├── api/
│   ├── application/
│   │   ├── command/
│   │   ├── query/
│   │   ├── handler/
│   │   └── service/
│   ├── domain/
│   │   ├── model/
│   │   ├── valueobject/
│   │   ├── event/
│   │   ├── policy/
│   │   ├── repository/
│   │   └── exception/
│   ├── infrastructure/
│   │   ├── persistence/
│   │   ├── messaging/
│   │   ├── outbox/
│   │   ├── security/
│   │   └── observability/
│   └── config/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
└── src/test/
```

---

## 22. Design Document Structure

```text
02-Ticket-Workflow/
├── README_CN.md
├── README_EN.md
├── 01-domain-model/
├── 02-business-invariants/
├── 03-state-machine/
├── 04-use-cases/
├── 05-api-contracts/
├── 06-event-contracts/
├── 07-data-model/
├── 08-transaction-and-outbox/
├── 09-concurrency-and-idempotency/
├── 10-failure-handling/
├── 11-security/
├── 12-observability/
├── 13-package-and-class-design/
├── 14-testing-strategy/
└── diagrams/
```

Recommended order:

```text
Domain Model
→ Business Invariants
→ State Machine
→ Use Cases
→ APIs
→ Events
→ Data Model
→ Transactions and Outbox
→ Concurrency and Idempotency
→ Failure Handling
→ Security
→ Observability
→ Package/Class Design
→ Testing
```

---

## 23. MVP Scope

### In Scope

- Identity / MFA tickets
- Employee ticket creation
- Classification results
- User messages
- Waiting for user
- Waiting for approval
- Tool results
- Verification results
- Resolve, close, cancel, reopen, and escalate
- Status history
- Basic SLA
- Transactional outbox
- Idempotency
- Optimistic locking
- OpenTelemetry

### Out of Scope

- ServiceNow replacement
- Multi-tenancy
- Workflow builder
- Advanced SLA editor
- Multi-region availability
- Event sourcing
- Full CQRS
- Kafka
- Production Okta or Duo
- Direct LangSmith SDK inside Ticket Service
- Advanced search
- Complex reporting

---

## 24. Design Completion Criteria

- [ ] Aggregate root finalized
- [ ] Entities and value objects finalized
- [ ] Aggregate boundary finalized
- [ ] Business invariants finalized
- [ ] State machine finalized
- [ ] Illegal transitions finalized
- [ ] Use cases finalized
- [ ] Commands and queries finalized
- [ ] API contracts finalized
- [ ] Event contracts finalized
- [ ] Data model finalized
- [ ] Transaction boundaries finalized
- [ ] Outbox finalized
- [ ] Optimistic locking finalized
- [ ] API and event idempotency finalized
- [ ] Failure handling finalized
- [ ] Security finalized
- [ ] Observability finalized
- [ ] Package and class design finalized
- [ ] Test plan finalized
- [ ] Golden Path sequence reviewed

---

## 25. Immediate Next Step

After this README, create:

```text
01-domain-model/
02-business-invariants/
03-state-machine/
```

Freeze these before moving to use cases, APIs, events, and database design.

Do not implement the complete Spring Boot service yet.
