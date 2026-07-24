# OpsMind Ticket Workflow — Implementation Roadmap

> **Document ID:** IMP-TW-000  
> **Domain:** `02-ticket-workflow`  
> **Document Type:** Implementation Roadmap  
> **Version:** 1.1  
> **Status:** Reviewed Draft  
> **Delivery Method:** Spec-Driven Development + Test-Driven Development + Vertical Slice Delivery  
> **Design Baseline:** `docs/low-level-design/domains/02-ticket-workflow/`  
> **Code Directory:** `services/ticket-workflow-service/`  
> **Feature Spec Directory:** `docs/specs/domains/02-ticket-workflow/`  
> **Traceability Directory:** `docs/traceability/domains/02-ticket-workflow/`

---

# 1. Purpose

This document defines how the approved Ticket Workflow Low-Level Design will be converted into production-oriented, executable code.

It defines:

- Implementation phases and ordering
- The reason for that ordering
- Objectives, scope, and non-goals for each phase
- Design documents referenced by each phase
- Feature Specifications required by each phase
- The SDD and TDD workflow
- Deliverables and exit criteria
- Cross-phase quality gates
- Traceability between design, specifications, tests, and implementation

This roadmap does not redesign Ticket Workflow and does not duplicate the fourteen LLD documents.

It answers:

```text
Now that the intended system design is known,
what should be implemented first,
why should it be implemented in this order,
and what must be complete before the next phase begins?
```

---

# 2. Review Decisions

The review of phase boundaries, directories, technical dependencies, and Phase 00 scope produced the following decisions.

## 2.1 Phase Structure

The overall Phase 00–10 sequence is retained.

Reasons:

- The sequence follows the Ticket lifecycle and code dependency direction.
- Each phase contains multiple small Feature Specs rather than one large requirement.
- A phase is a delivery milestone; a Feature Spec is the smallest review and TDD unit.
- Phases that depend on Agent, Approval, Tool, or Verification behavior can use contract-driven stubs before the real services are available.

## 2.2 Directories

Use the following consistent structure:

```text
docs/implementation-plans/domains/02-ticket-workflow/
docs/specs/domains/02-ticket-workflow/
docs/traceability/domains/02-ticket-workflow/
services/ticket-workflow-service/
```

The `domains/` level is included under `traceability` for consistency with implementation plans and specifications.

## 2.3 Technical Baseline

The Ticket Workflow implementation line is frozen to:

```text
Java 21
Spring Boot 3.5.16
Maven 3.9.16 Wrapper
PostgreSQL 18.4
RabbitMQ 4.3.4
Keycloak 26.7.0
Testcontainers 2.0.5
```

Spring Boot 4.1.0 is not silently adopted in this implementation line. A future major-version migration requires a Technology Baseline update and an ADR.

## 2.4 Phase 00 Scope

Phase 00 establishes a trustworthy build, test, configuration, security, and infrastructure-connectivity foundation.

Phase 00 does not create:

- The Ticket aggregate
- Ticket business tables
- Ticket APIs
- Ticket integration events
- Transactional Outbox business behavior

## 2.5 Cross-cutting Capabilities

Security, audit, idempotency, transactions, and observability cannot all be postponed until Phase 09.

Every vertical slice implements the minimum cross-cutting behavior required by its Feature Spec. Phase 09 is a hardening and completeness phase.

---

# 3. Design Baseline

The following Ticket Workflow LLD documents are complete:

```text
01-domain-model
02-business-invariants
03-state-machine
04-use-cases
05-api-contracts
06-event-contracts
07-data-model
08-transaction-and-outbox
09-concurrency-and-idempotency
10-error-handling-and-reconciliation
11-security-and-authorization
12-observability-and-audit
13-package-and-class-design
14-testing-strategy
```

Together they form the implementation source of truth.

Implementation must not:

- Bypass a business invariant
- Introduce a generic status-mutation endpoint
- Break Transactional Outbox for demo convenience
- Allow consumers to skip idempotency
- Resolve a Ticket directly from Tool Success
- Allow controllers, schedulers, or reconciliation code to modify JPA entities directly
- Change code without updating affected LLD, specifications, and tests

---

# 4. Why an Implementation Roadmap Is Required

Each artifact answers a different question:

| Layer | Question |
|---|---|
| LLD | What should the system look like? |
| Implementation Roadmap | What is implemented first, next, and why? |
| Feature Spec | How must one feature behave? |
| Test | How is compliance verified by automation? |
| Code | How are those tests made to pass? |

Without a roadmap, teams commonly:

- Create many classes without delivering a runnable feature
- Implement consumers before the aggregate is stable
- Integrate Agent Runtime before `ticket.created` and workflow identity exist
- Implement controllers before domain state rules
- Attempt all state transitions in one change
- Lose the relationship between commits and use cases

---

# 5. Delivery Method

Ticket Workflow uses:

```text
LLD Baseline
→ Implementation Phase
→ Feature Spec
→ Failing Tests
→ Minimum Implementation
→ Refactor
→ Integration Verification
→ Traceability Update
```

This combines:

```text
Spec-Driven Development
+
Test-Driven Development
+
Vertical Slice Delivery
```

---

# 6. Spec-Driven Development

Every business slice has a small and explicit Feature Spec before coding begins.

A Feature Spec references rather than copies:

- Use Case IDs
- API IDs
- State Transition IDs
- Business Invariant IDs
- Event Contracts
- Data tables
- Security scopes
- Audit requirements
- Observability requirements
- Testing requirements

Example:

```text
SPEC-TW-001 Create Ticket

References:
- UC-01
- API-001
- SM-001
- BI-001
- BI-008
- ticket.created.v1
- ticket.tickets
- ticket.ticket_status_history
- ticket.outbox_events
```

The Feature Spec adds implementation-slice details:

- Scope
- Non-goals
- Preconditions
- Detailed behavior
- Transaction boundary
- Acceptance scenarios
- Error scenarios
- Tests-first plan
- Definition of Done

---

# 7. Test-Driven Development

Every Feature Spec follows:

```text
RED
→ GREEN
→ REFACTOR
→ VERIFY
```

## RED

Write failing tests first:

- Domain unit tests
- Application tests
- API or event contract tests
- Required integration tests

The tests must fail because the behavior is not implemented, not because the environment is broken.

## GREEN

Write the minimum code required by the current Feature Spec.

“Minimum” means:

```text
Implement only behavior required by the current Feature Spec,
while following all approved architecture and business constraints.
```

## REFACTOR

Under test protection:

- Improve names
- Split responsibilities
- Remove duplication
- Strengthen types
- Preserve package dependencies
- Preserve API and event compatibility

## VERIFY

Run:

- Unit tests
- Application tests
- Integration tests
- Contract tests
- Security tests
- Architecture tests
- Observability checks

A Spec is complete only after the traceability matrix is updated.

---

# 8. Why Vertical Slices

Each phase delivers an end-to-end path from an entry point to persistence or an event boundary.

Example:

```text
POST /api/v1/tickets
→ Authentication
→ Authorization
→ CreateTicketApplicationService
→ Ticket.create()
→ PostgreSQL
→ Status History
→ Audit
→ Outbox ticket.created
→ HTTP Response
```

The slice includes:

- API
- Application
- Domain
- Persistence
- Transaction
- Outbox
- Security
- Audit
- Observability
- Tests

This is preferred over implementing all controllers, repositories, or entities horizontally before connecting them.

Benefits:

- Every phase produces a demonstrable result.
- Design conflicts are discovered earlier.
- Pull Requests have clear intent.
- Regression is isolated.
- Project evolution is easier to explain.
- Large batches of unverified generated code are avoided.

---

# 9. Contract-first Cross-domain Integration Policy

Some Ticket Workflow phases depend on:

```text
Agent Runtime
Policy and Approval
Tool Integration Gateway
Verification
Notification
```

The absence of the real service does not block the Ticket Workflow slice.

Use:

```text
Approved Event Contract
→ Golden JSON Fixture
→ Deterministic Stub Producer or Consumer
→ Ticket Workflow Integration Test
→ Real Service Compatibility Test
```

Rules:

- Stubs must use the approved envelope and schema.
- Stubs must never access the Ticket Workflow database.
- Outcomes must be deterministic and controlled by a scenario ID.
- The real service must pass the same contract tests when integrated.
- Consumers must not silently accept arbitrary payloads to compensate for an invalid producer.

---

# 10. Phase Overview

```text
Phase 00  Engineering Foundation
Phase 01  Create Ticket Vertical Slice
Phase 02  Ticket Query and Message Slice
Phase 03  Triage and Investigation Slice
Phase 04  Waiting for User Slice
Phase 05  Policy and Approval Slice
Phase 06  Tool Execution Slice
Phase 07  Verification and Resolution Slice
Phase 08  Close, Reopen, Cancel, Assign and Escalate Slice
Phase 09  Security, Audit and Operational Hardening
Phase 10  Reconciliation, Chaos and Release Readiness
```

All phases remain part of:

```text
02-ticket-workflow
```

Implementation code lives in:

```text
services/ticket-workflow-service/
```

---

# 11. Minimum Cross-cutting Baseline for Every Slice

Beginning with Phase 01, every Feature Spec checks:

```text
Authentication or Service Identity
Authorization
Input or Event Contract Validation
Business Invariants
Transaction Boundary
Idempotency when applicable
History
Audit when required
Outbox when an event is emitted
Structured Error Handling
Tracing and Metrics
Unit, Integration, and Contract Tests
```

Phase 09 does not repair earlier business code that completely omitted security or audit.

Phase 09 focuses on:

- Full Keycloak realm and client integration
- Queue authorization hardening
- Step-up authentication
- Sensitive-read audit
- Secret-detection hardening
- Dashboards, alerts, and rate limits
- Cross-cutting regression verification

---

# 12. Phase 00 — Engineering Foundation

## Objective

Build the engineering and test foundation required for SDD and TDD without implementing Ticket business behavior.

## Why First

Reliable TDD requires:

- A Java and Spring Boot project
- Maven Wrapper
- JUnit
- Testcontainers
- PostgreSQL
- RabbitMQ
- ArchUnit
- CI
- Configuration management
- Health checks

Phase 00 is the implementation environment, not a business feature.

## Main Design References

```text
13-package-and-class-design
14-testing-strategy
12-observability-and-audit
11-security-and-authorization
technology-baseline
```

## Deliverables

- `services/ticket-workflow-service/`
- Java 21 and Spring Boot
- Maven Wrapper
- Minimal package structure
- Base configuration
- PostgreSQL and RabbitMQ Testcontainers
- ArchUnit
- CI Fast Verify
- Health and readiness
- Initial README

## Exit

```text
./mvnw clean verify
```

passes, and:

- Spring context starts.
- PostgreSQL and RabbitMQ Testcontainers start.
- ArchUnit rules execute.
- CI executes.
- No Ticket business code exists.

Detailed plan:

```text
phase-00-engineering-foundation_EN.md
```

---

# 13. Phase 01 — Create Ticket Vertical Slice

## Objective

Deliver the first complete business slice:

```text
Authenticated Employee
→ Create Ticket
→ NEW
→ Status History
→ Audit
→ Outbox ticket.created
→ Response
```

## Why Now

Ticket creation is the entry point for every workflow.

It validates:

- Ticket aggregate
- `SM-001`
- UC-01
- API-001
- PostgreSQL persistence
- Transactional Outbox
- API idempotency
- Resource ownership
- Audit
- Tracing and metrics

It also emits the event required by Agent Runtime:

```text
ticket.created.v1
```

## Feature Spec

```text
SPEC-TW-001-create-ticket
```

## Exit Criteria

- A repeated identical Idempotency Key does not create a second Ticket.
- Ticket, history, audit, and Outbox commit atomically.
- `ticket.created.v1` passes contract validation.
- The initial status is always `NEW`.
- All Phase 01 tests pass.

---

# 14. Phase 02 — Ticket Query and Message Slice

## Objective

Allow Employee and Support actors to read authorized Ticket information and append messages.

## Scope

```text
Get Ticket
List My Tickets
List Support Queue
Get Timeline
Add Requester Message
Add Internal Support Message
```

## Why Now

Agent, Support, and requester interaction require stable read and message capabilities.

This phase validates:

- Read models
- Cursor pagination
- Field visibility
- Resource ownership
- Queue authorization
- Append-only messages
- Timeline composition

## Feature Specs

```text
SPEC-TW-002-get-ticket
SPEC-TW-003-list-requester-tickets
SPEC-TW-004-add-ticket-message
SPEC-TW-005-support-queue-query
SPEC-TW-006-ticket-timeline
```

## Exit Criteria

- Employees cannot read another requester’s Ticket.
- Internal messages are hidden from Employees.
- Cursor pagination is stable.
- Messages are append-only.
- Queries do not rehydrate the full aggregate.

---

# 15. Phase 03 — Triage and Investigation Slice

## Objective

Implement:

```text
NEW
→ TRIAGING
→ INVESTIGATING
```

and associate the Ticket with an Agent workflow.

## Why Now

After create, query, and message behavior exists, the Ticket can safely enter automated orchestration.

This phase validates:

- Service-to-service events
- Active workflow identity
- Agent Runtime references
- Classification results
- Event deduplication
- Stale workflow handling

## Feature Specs

```text
SPEC-TW-007-start-triage
SPEC-TW-008-complete-classification
SPEC-TW-009-agent-workflow-failure
```

## Exit Criteria

- A Ticket has at most one active workflow.
- Classification events match the Ticket and workflow.
- Duplicate transport delivery has no duplicate business effect.
- Old-workflow events are classified as stale.

---

# 16. Phase 04 — Waiting for User Slice

## Objective

Implement:

```text
TRIAGING / INVESTIGATING
→ WAITING_FOR_USER
→ TRIAGING / INVESTIGATING
```

## Why Before Approval

Requesting additional information is one of the most common IT-support branches. Approval and tool execution should not be built around an incomplete interaction model.

## Feature Specs

```text
SPEC-TW-010-request-user-input
SPEC-TW-011-user-reply-and-resume
```

## Key Requirements

- Only one open input request exists per Ticket.
- A reply references the current request.
- `WAITING_FOR_USER` pauses the SLA.
- A reply cannot resume an obsolete workflow.
- Message and status mutation commit atomically.

---

# 17. Phase 05 — Policy and Approval Slice

## Objective

Implement:

```text
INVESTIGATING
→ WAITING_FOR_APPROVAL
→ EXECUTING
```

and:

```text
REJECTED / EXPIRED
→ INVESTIGATING
```

## Why Before Tool Execution

Tool Gateway must not decide high-risk authorization by itself. Approval is a precondition for controlled execution.

## Feature Specs

```text
SPEC-TW-012-request-approval
SPEC-TW-013-apply-approval-granted
SPEC-TW-014-apply-approval-rejected
SPEC-TW-015-apply-approval-expired
SPEC-TW-016-apply-auto-approved-policy
```

## Key Requirements

- Approval is bound to Ticket, workflow, action, and risk context.
- Expired approval cannot authorize execution.
- Approval cannot be reused.
- A wrong producer is routed to DLQ.
- Duplicate approval is idempotent.

---

# 18. Phase 06 — Tool Execution Slice

## Objective

Implement:

```text
EXECUTING
→ VERIFYING
```

plus known-safe failure, unknown result, and internal failure paths.

## Feature Specs

```text
SPEC-TW-017-tool-execution-completed
SPEC-TW-018-tool-execution-failed
SPEC-TW-019-tool-result-unknown
```

## Key Requirements

- Tool Success never resolves a Ticket directly.
- Tool execution matches the pending action.
- Unknown result is not blindly retried.
- Unknown side effects require verification or escalation.
- A ToolExecutionId cannot cause duplicate business effects.

---

# 19. Phase 07 — Verification and Resolution Slice

## Objective

Implement:

```text
VERIFYING
→ RESOLVED
```

plus verification retry and escalation behavior.

## Feature Specs

```text
SPEC-TW-020-start-verification
SPEC-TW-021-verification-success
SPEC-TW-022-verification-failure
SPEC-TW-023-resolve-ticket
```

## Key Requirements

- A proposal is not verification.
- Only a trusted result for the current workflow, cycle, and attempt can resolve.
- The third failed attempt or an unsafe result escalates.
- `RESOLVED` is not `CLOSED`.
- The resolution cycle is persisted completely.

---

# 20. Phase 08 — Lifecycle Completion Slice

## Objective

Complete the main human and terminal lifecycle operations:

```text
Close
Auto-close
Reopen
Cancel
Assign
Escalate
Resume from Escalation
Retry from Failed
```

## Feature Specs

```text
SPEC-TW-024-confirm-resolution
SPEC-TW-025-auto-close
SPEC-TW-026-reopen-ticket
SPEC-TW-027-cancel-ticket
SPEC-TW-028-assign-ticket
SPEC-TW-029-escalate-ticket
SPEC-TW-030-resume-escalated-ticket
```

## Key Requirements

- Closed Tickets may reopen within the defined window.
- Reopen creates a new workflow, resolution cycle, and SLA cycle.
- Cancelled Tickets cannot reopen in the MVP.
- EXECUTING and VERIFYING cannot be cancelled unsafely.
- Assignment is constrained by queue authorization.

---

# 21. Phase 09 — Security, Audit and Operational Hardening

## Objective

Complete and harden the cross-cutting security and operational capabilities already introduced slice by slice.

## Scope

- Keycloak realm and client integration
- Role and scope hardening
- Support queue authorization
- Field visibility
- Secret detection
- Step-up authentication
- Sensitive-read audit
- OpenTelemetry
- Metrics
- Dashboards
- Alerts
- Rate limits

## Why Security Is Not Deferred

Authentication, authorization, audit, and telemetry are added in earlier phases where required.

Phase 09 is a completeness and hardening phase, not the first security implementation.

## Feature Specs

```text
SPEC-TW-031-support-queue-authorization
SPEC-TW-032-sensitive-read-audit
SPEC-TW-033-secret-detection
SPEC-TW-034-step-up-authentication
```

---

# 22. Phase 10 — Reconciliation, Chaos and Release Readiness

## Objective

Prove that the system can recover safely from duplication, reordering, crashes, unknown outcomes, and cross-service conflicts.

## Scope

- Reconciliation cases
- DLQ triage
- Event replay
- Correction events
- Compensation
- Integrity scans
- Crash windows
- Chaos tests
- Performance tests
- Release gates

## Why Last

Reconciliation depends on the state machine, events, Outbox, idempotency, Tool, Verification, audit, and security.

Unknown-result hooks must nevertheless be introduced from Phase 06.

## Feature Specs

```text
SPEC-TW-035-open-reconciliation-case
SPEC-TW-036-replay-event
SPEC-TW-037-correction-event
SPEC-TW-038-compensation
SPEC-TW-039-data-integrity-repair
```

---

# 23. Standard Phase Plan Structure

Every phase plan contains:

```text
1. Objective
2. Why This Phase Now
3. Design References
4. Included Feature Specs
5. Scope
6. Non-goals
7. Architecture Decisions Applied
8. TDD Execution Order
9. Implementation Tasks
10. Test Plan
11. Deliverables
12. Risks
13. Exit Criteria
14. Traceability Update
```

---

# 24. Standard Feature Spec Structure

```text
1. Spec Identity
2. Objective
3. Design References
4. Actor
5. Scope
6. Non-goals
7. Preconditions
8. Command or Input
9. Detailed Behavior
10. State Transition
11. Business Invariants
12. Transaction Boundary
13. Events
14. Security
15. Audit
16. Observability
17. Error Scenarios
18. Acceptance Scenarios
19. Tests First
20. Definition of Done
```

---

# 25. Traceability

Maintain:

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

Example:

```yaml
SPEC-TW-001:
  phase: Phase-01

  design:
    use_cases:
      - UC-01
    api:
      - API-001
    transitions:
      - SM-001
    invariants:
      - BI-001
      - BI-008
    events:
      - ticket.created.v1

  implementation:
    classes:
      - Ticket
      - CreateTicketApplicationService
      - PublicTicketController
      - TicketPersistenceAdapter

  tests:
    - TicketCreationTest
    - CreateTicketApplicationServiceTest
    - CreateTicketControllerTest
    - CreateTicketAtomicityIT
```

---

# 26. Pull Request Strategy

One Feature Spec should normally map to one or a few small Pull Requests.

Recommended sequence:

```text
docs(spec): define SPEC-TW-001 create ticket
test(ticket): add failing create ticket domain tests
feat(ticket): implement ticket creation domain behavior
feat(persistence): add create ticket migrations and adapter
feat(api): implement create ticket endpoint
test(integration): verify ticket creation transaction and outbox
docs(traceability): link SPEC-TW-001 to code and tests
```

Avoid:

```text
Implement all Ticket Workflow
```

---

# 27. Design Change Rules

## Small Clarification

For naming or test-support changes:

- Update the Feature Spec.
- Update the LLD when necessary.
- Explain the change in the Pull Request.

## Architecture or Business-semantic Change

Examples:

- New state
- New transition
- Changed approval trust boundary
- Changed event semantics
- Changed transaction boundary
- Changed security model

Required sequence:

```text
ADR or LLD Change
→ Review
→ Feature Spec Update
→ Test Update
→ Code Change
```

Code-only changes are not accepted.

---

# 28. Cross-phase Quality Gates

Every phase requires:

- Reviewed Feature Specs
- Tests submitted before or with implementation
- Coverage for critical invariants
- No unapproved breaking API or event change
- No Spring or JPA dependency in Domain
- Real PostgreSQL integration tests
- Atomic business state and Outbox
- No secrets in logs, traces, or events
- Passing ArchUnit rules
- Updated traceability
- Updated README and run commands

---

# 29. MVP and Full Design

Recommended MVP boundary:

```text
Phase 00
→ Phase 01
→ Phase 02
→ Phase 03
→ Phase 04
→ Phase 05
→ Phase 06
→ Phase 07
→ Core paths from Phase 08
```

The portfolio demo should at minimum show:

```text
Create
→ Triage
→ Approval
→ Tool
→ Verification
→ Resolve
```

Phase 09 and Phase 10 can be classified as:

```text
MVP Required
Portfolio Hardening
Production-oriented Extension
```

Security boundaries declared in the design cannot be bypassed for MVP convenience.

---

# 30. Roadmap Completion

The roadmap is complete when:

- Every planned phase has a status.
- Every implemented use case has a Feature Spec.
- Every Spec is traceable to LLD.
- Every Spec is traceable to tests and code.
- Golden Path E2E passes.
- Critical failure paths pass.
- Release gates pass.
- README documentation allows a new developer to run and verify the service.

---

# 31. Immediate Next Steps

```text
1. Review this roadmap
2. Review phase-00-engineering-foundation_EN.md
3. Approve the directory and technical baseline
4. Create the Phase 00 project foundation
5. Satisfy Phase 00 exit criteria
6. Write SPEC-TW-001-create-ticket_EN.md
7. Enter the RED stage of Phase 01
```
