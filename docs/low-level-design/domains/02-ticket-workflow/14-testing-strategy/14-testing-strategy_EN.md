# OpsMind Ticket Workflow — 14 Testing Strategy

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Testing Strategy and Quality Gate  
> **Version:** 1.0  
> **Status:** Proposed for Implementation  
> **Dependencies:** `01-domain-model_EN.md` through `13-package-and-class-design_EN.md`  
> **Test Technologies:** JUnit 5, AssertJ, Mockito, Spring Boot Test, MockMvc, Testcontainers, WireMock, ArchUnit, Awaitility, Pact, k6 or Gatling  
> **Target Platform:** Java 21, Spring Boot, PostgreSQL, RabbitMQ, Keycloak, OpenTelemetry  
> **Recommended Path:** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/14-testing-strategy_EN.md`

---

# 1. Purpose

This document defines the testing layers, boundaries, quality gates, CI execution order, and release criteria for Ticket Workflow.

Core goals:

```text
Every business invariant has executable coverage.
Every state transition has allowed and rejected-path tests.
Every integration event is tested for schema, duplication, delay, ordering, and conflict.
Database and messaging guarantees are verified on real PostgreSQL and RabbitMQ.
Every high-risk operation is tested for authorization, approval, audit, and verification.
A release never depends only on manual clicking.
```

---

# 2. Testing Principles

## 2.1 Test Behavior, Not Implementation Detail

Tests verify:

```text
Given a business state
When a business action occurs
Then a legal outcome results
```

Avoid excessive assertions about private calls, internal collection order, and meaningless getters.

## 2.2 More Tests Near the Domain

Complex business rules belong in fast Domain Unit Tests. PostgreSQL, RabbitMQ, Keycloak, and OpenTelemetry semantics belong in integration tests.

## 2.3 Do Not Mock Critical Infrastructure Semantics

The following require real integration verification:

- PostgreSQL constraints
- Optimistic locking
- `FOR UPDATE SKIP LOCKED`
- RabbitMQ redelivery
- Publisher confirms
- DLQ behavior
- JSONB
- Flyway
- Keycloak claims
- Trace-context propagation

## 2.4 Every Bug Adds a Regression Test

A bug-fix pull request includes a test reproducing the original failure and identifies the affected invariant, transition, or use case.

## 2.5 Failures Must Be Diagnosable

Failure output includes:

- Expected and actual results
- Ticket status
- Aggregate version
- EventId or CommandId
- Relevant database-record summary
- Container-log reference

---

# 3. Test Pyramid

Recommended distribution:

```text
Domain Unit Test              40%
Application Unit / Slice      20%
Persistence / Messaging       20%
API / Security Contract       10%
End-to-End / Chaos / Load     10%
```

The service must not depend on a large, slow E2E suite while lacking domain tests.

---

# 4. Test Levels

```text
L0 Static Analysis
L1 Domain Unit
L2 Application Unit
L3 Component / Slice
L4 Infrastructure Integration
L5 Contract
L6 End-to-End
L7 Chaos / Performance / Security
```

## L0 Static Analysis

- Compilation
- Checkstyle
- SpotBugs or Error Prone
- Dependency scan
- Secret scan
- ArchUnit
- JSON Schema lint
- OpenAPI lint

## L1 Domain Unit

Does not start Spring and does not access databases or brokers.

## L2 Application Unit

Mocks outbound ports and verifies use-case orchestration.

## L3 Component / Slice

Uses `@WebMvcTest`, `@DataJpaTest`, security slices, and mapper tests.

## L4 Infrastructure Integration

Uses real PostgreSQL, RabbitMQ, and Keycloak Testcontainers.

## L5 Contract

Validates stable API and event contracts.

## L6 End-to-End

Runs the complete Ticket Workflow service and required stubs.

## L7 Non-functional

Covers chaos, load, soak, and dynamic security.

---

# 5. Test Naming

Pattern:

```text
should<ExpectedBehavior>When<Condition>
```

Examples:

```text
shouldMoveToVerifyingWhenToolExecutionSucceeds
shouldRejectResolutionWithoutTrustedVerification
shouldReturnStoredResponseWhenIdempotencyKeyIsReplayed
shouldAckOldWorkflowEventAsStale
```

Use `@Nested` to group tests by business operation.

---

# 6. Given / When / Then

```java
// Given
Ticket ticket = TicketFixtures.investigatingTicket();

// When
ticket.cancel(reason, actor, now, policy);

// Then
assertThat(ticket.status()).isEqualTo(CANCELLED);
```

Setup, execution, and assertions remain visibly separated.

---

# 7. Business-Invariant Testing

Every Critical and High invariant in `02-business-invariants` has at least one automated test.

Recommended names include the invariant ID:

```text
BI032_shouldAllowOnlyOneActiveWorkflow
BI048_shouldRejectApprovalForDifferentAction
BI067_shouldRequireCurrentVerificationBeforeResolve
```

## 7.1 Invariant Coverage Matrix

Maintain:

```text
src/test/resources/invariant-coverage.yaml
```

Example:

```yaml
BI-032:
  - TicketWorkflowInvariantTest#shouldAllowOnlyOneActiveWorkflow
BI-048:
  - ApprovalReferenceInvariantTest#shouldRejectApprovalForDifferentAction
```

CI reports:

```text
Invariant ID
Test class
Test method
Result
```

## 7.2 Mandatory Invariant Coverage

- One active workflow
- One active pending action
- Tool success cannot directly resolve
- Verification is independent from proposal
- Resolution matches the current cycle, workflow, and attempt
- `RESOLVED != CLOSED`
- Reopen creates a new workflow, resolution cycle, and SLA cycle
- Unknown tool result is not blindly retried
- Ticket, history, and Outbox commit atomically
- Processed Event commits atomically with business state
- Same EventId with a different payload goes to DLQ
- Terminal Tickets reject old-cycle events
- Optimistic conflicts reload and re-evaluate

---

# 8. State-Machine Testing

Every transition from `SM-001` through `SM-034` has:

```text
one happy-path test
one guard-failure test
one side-effect assertion
```

Each transition verifies:

- Source status
- Trigger
- Required references
- Target status
- Version increment
- Status history
- Domain event
- Outbox event type
- SLA effect
- Pending-action effect

## 8.1 Illegal Transitions

Use parameterized tests across stable states and illegal actions.

## 8.2 Terminal States

Verify:

- Ordinary late events do not advance `CLOSED` or `CANCELLED`
- Closed Tickets may reopen within the configured window
- Cancelled Tickets do not reopen in the MVP
- Stale and audit records may still be stored

## 8.3 Verification Failure Counter

```text
failure 1 → INVESTIGATING
failure 2 → INVESTIGATING
failure 3 → ESCALATED
unsafe result → ESCALATED immediately
```

---

# 9. Domain Unit Tests

Package:

```text
src/test/java/.../ticket/domain
```

Suites:

```text
TicketCreationTest
TicketTransitionTest
TicketCancellationTest
TicketReopenTest
TicketResolutionTest
TicketAssignmentTest
TicketEscalationTest
TicketVerificationPolicyTest
TicketPendingActionPolicyTest
TicketSlaPolicyTest
ValueObjectValidationTest
```

Domain tests do not use:

```text
@SpringBootTest
@DataJpaTest
PostgreSQL
RabbitMQ
```

Value-object tests cover null, blank, length, format, equality, and boundaries.

Domain-event tests verify event type, Ticket identity, state, version, time, actor, and absence of secrets or raw bodies.

---

# 10. Application Unit Tests

Package:

```text
src/test/java/.../ticket/application
```

Mock:

- Repository ports
- Authorization port
- Idempotency port
- Audit port
- Outbox port
- Clock
- Identifier generators

Do not mock the Ticket aggregate.

Suites:

```text
CreateTicketApplicationServiceTest
AddTicketMessageApplicationServiceTest
CancelTicketApplicationServiceTest
ReopenTicketApplicationServiceTest
ApprovalEventApplicationServiceTest
ToolEventApplicationServiceTest
VerificationEventApplicationServiceTest
AssignmentApplicationServiceTest
EscalationApplicationServiceTest
```

Verify:

- Authorization before domain behavior
- Idempotency replay avoids duplicate persistence
- Expected version propagation
- History, audit, and Outbox writes
- Error mapping
- Reconciliation triggers
- Result objects

Interaction verification is limited to meaningful boundaries.

---

# 11. API Controller Tests

Use:

```text
@WebMvcTest
MockMvc
Spring Security Test
```

Test:

- Routes
- Validation
- Scopes
- Request mapping
- Response mapping
- Error envelope
- `If-Match`
- `Idempotency-Key`
- Cursor pagination
- Content type

Validation covers missing fields, blank titles, oversized descriptions, invalid identifiers, invalid enums, unknown fields, and oversized payloads.

Error responses never expose stack traces.

---

# 12. API Contract Tests

Validate:

- Unique OperationId
- Request schemas
- Response schemas
- Error schemas
- Security requirements
- Status codes
- Headers
- Pagination

Without an API version change, do not:

- Remove fields
- Change field types
- Make optional fields required
- Remove status codes
- Change stable enum values
- Change stable error semantics

CI performs a breaking-change diff between the main-branch OpenAPI and the current contract.

---

# 13. Event Contract Tests

Package:

```text
src/test/java/.../ticket/messaging/contract
```

Every published and consumed event tests:

- Envelope
- Schema
- Version
- Routing key
- Producer
- Required references
- Data classification
- Secret-free payload
- Unknown-field policy

Golden fixtures live under:

```text
src/test/resources/contracts/events/
```

Examples:

```text
approval-granted-v1.valid.json
approval-granted-v1.invalid-missing-action.json
ticket-resolved-v1.valid.json
```

Published-event tests cover:

```text
Domain Event → Integration Event → JSON
```

Consumed-event tests cover:

```text
Fixture JSON → DTO → Application Command
```

---

# 14. Consumer Contract Tests

Each consumer validates:

- Allowed producer
- Wrong producer
- Schema failure
- Duplicate
- Business duplicate
- Stale
- Out of order
- Corrupt reference
- Terminal-result conflict
- ACK, retry, and DLQ

Suites:

```text
ApprovalEventConsumerContractTest
ToolExecutionEventConsumerContractTest
VerificationEventConsumerContractTest
```

---

# 15. PostgreSQL Integration Tests

Use:

```text
PostgreSQL Testcontainer
Flyway
real schema
```

H2 is not accepted for critical persistence tests.

Core suites:

```text
TicketPersistenceAdapterIT
TicketHistoryPersistenceIT
OutboxPersistenceIT
ProcessedEventPersistenceIT
IdempotencyPersistenceIT
TicketQueryRepositoryIT
FlywayMigrationIT
```

Validate:

- PK and FK
- Unique constraints
- Check constraints
- Partial unique indexes
- Versioning
- JSONB
- Cursor pagination
- Timeline ordering
- Role-based visibility

Partial-unique tests include:

```text
one active workflow
one active pending action
one open user request
one active SLA cycle
one cycle number per Ticket
```

---

# 16. Flyway Tests

Each migration suite:

1. Migrates an empty database.
2. Upgrades from the previous stable schema.
3. Verifies constraints.
4. Verifies reference data.
5. Runs `hibernate.ddl-auto=validate`.
6. Verifies the roll-forward recovery plan.

---

# 17. Optimistic-Lock Tests

Real PostgreSQL scenario:

```text
Transaction A loads version 3
Transaction B loads version 3
A commits version 4
B fails
B reloads and re-evaluates
```

Tests:

```text
shouldAllowOnlyOneConcurrentAssignment
shouldRejectBlindRetryAfterStateChanges
shouldReturnIdempotentSuccessWhenEquivalentResultAlreadyApplied
```

---

# 18. Transaction Atomicity Tests

Failure injection verifies atomicity across:

```text
Ticket
History
Audit
Outbox
Processed Event
Idempotency Response
```

Required tests:

```text
shouldRollbackTicketWhenHistoryInsertFails
shouldRollbackTicketWhenOutboxInsertFails
shouldRollbackProcessedEventWhenTicketUpdateFails
shouldRollbackHighRiskActionWhenAuditInsertFails
```

---

# 19. RabbitMQ Integration Tests

Use:

```text
RabbitMQ Testcontainer
real exchanges
real queues
real DLQs
publisher confirms
```

Suites:

```text
RabbitMqTopologyIT
OutboxPublisherIT
ApprovalConsumerIT
ToolExecutionConsumerIT
VerificationConsumerIT
RetryQueueIT
DlqIT
TracePropagationIT
```

Validate:

- Durable exchanges and queues
- Bindings
- Routing keys
- Retry TTL
- DLX
- Single active consumer
- Publisher confirms
- Redelivery
- Commit before ACK

---

# 20. Outbox Publisher Tests

Test:

- Batch claims
- `FOR UPDATE SKIP LOCKED`
- Multiple publishers
- Lock-timeout recovery
- Broker ACK and NACK
- Confirm timeout
- Unroutable message
- Retry backoff
- Retry exhaustion
- Duplicate publication

Core tests:

```text
shouldClaimEachRowByOnlyOnePublisher
shouldNotHoldDatabaseLockWhileWaitingForConfirm
shouldMarkPublishedOnlyAfterAck
shouldRepublishSameEventIdAfterPublisherCrash
```

---

# 21. API Idempotency Tests

Cover:

```text
same key + same payload + completed → replay
same key + different payload → 409
same key + fresh in-progress → 409
stale in-progress + committed resource → rebuild response
stale in-progress + no resource → retryable
```

Concurrency:

```text
100 concurrent identical create requests
→ exactly one Ticket
```

---

# 22. Event Idempotency Tests

Cover:

```text
same EventId + same hash → duplicate ACK
same EventId + different hash → DLQ
different EventId + same ApprovalId → business duplicate
old workflow → stale
missing predecessor → retry
```

---

# 23. Security Tests

## Authentication

```text
expired token
wrong issuer
wrong audience
unknown key ID
missing scope
user token on internal API
service token on employee API
```

## Authorization

```text
employee reads own Ticket
employee cannot read another Ticket
support reads an authorized queue
support is denied an unauthorized queue
auditor is read-only
administrator cannot bypass the state machine
```

## Field Visibility

- Employee cannot see internal notes.
- Agent cannot read recovery audit.
- Tool Gateway does not receive Ticket description.
- Auditor receives redacted content.

## Step-up and Separation of Duties

- Recovery requires MFA.
- An operator cannot approve their own high-risk recovery.
- Correction events require approval.
- Compensation requires a new approval.

---

# 24. Keycloak Integration Tests

Use a Keycloak Testcontainer or real decoder integration.

At minimum validate:

- Realm import
- Clients
- Roles
- Scopes
- Audience mapper
- Group claims
- Service accounts
- Token expiration

---

# 25. Secret and Redaction Tests

Inject:

```text
Bearer eyJ...
password=secret
api_key=abc
-----BEGIN PRIVATE KEY-----
MFA recovery code
```

Verify the values do not appear in:

- API responses
- Logs
- Traces
- Metrics
- Audit metadata
- Events
- LangSmith

The `secret_detected` metric must increase.

---

# 26. Concurrency Tests

Use:

- `ExecutorService`
- `CountDownLatch`
- `CyclicBarrier`
- Awaitility
- PostgreSQL Testcontainer

Cover:

- Duplicate creation
- Two assignments
- Cancel versus approval
- Approval granted versus expired
- Tool success versus failure
- Tool success versus unknown
- Verification success versus failure
- Reopen versus auto-close
- Confirm versus auto-close
- Multiple user replies
- Multiple auto-close workers
- Multiple Outbox publishers

Final assertions verify:

- One legal final state
- Continuous versions
- No duplicate history
- No duplicate business Outbox events
- No lost update
- Stable conflict errors

---

# 27. Race-condition Matrix

Maintain:

```text
src/test/resources/race-condition-matrix.yaml
```

Example:

```yaml
cancel_vs_approval:
  winner_a: CANCELLED
  winner_b: EXECUTING
  forbidden:
    - CANCELLED_AND_EXECUTING
    - DUPLICATE_TOOL_ACTION
```

---

# 28. Scheduler Tests

Test:

- Auto-close due and not due
- Reopen race
- Multiple workers
- SLA pause in WAITING_FOR_USER
- SLA active in WAITING_FOR_APPROVAL
- Cleanup batches
- Job-failure isolation

ArchUnit verifies that schedulers call use cases instead of directly modifying JPA entities.

---

# 29. Reconciliation Tests

Cover:

- Unknown tool result
- Tool terminal conflict
- Verification conflict
- Approval conflict
- Long-lived out-of-order event
- Stale idempotency reservation
- Data-integrity mismatch
- Replay
- Correction event
- Manual recovery
- Compensation

Assertions verify:

- Unsafe automation is frozen.
- Evidence remains immutable.
- Recovery uses normal use cases.
- Recovery audit is written.
- Original events remain unchanged.
- Correction uses a new EventId.
- Compensation uses new ActionId and ToolExecutionId values.

---

# 30. Audit Tests

Test:

- High-risk action and audit in one transaction
- Append-only storage
- Sensitive-read audit
- Recovery audit
- Before and after hashes
- Actor, client, and scopes
- TraceId and CommandId
- Audit Outbox event

Required examples:

```text
shouldRollbackCancelWhenAuditInsertFails
shouldPreventUpdateOfAuditRecord
shouldAppendCorrectionAuditInsteadOfMutatingOriginal
```

---

# 31. Observability Tests

## Trace

Validate W3C propagation, HTTP-to-Outbox-to-RabbitMQ-to-consumer flow, span names, attributes, error status, and span links.

## Log

Validate JSON, TraceId, CorrelationId, ErrorCode, and redaction.

## Metric

Validate counters, histograms, gauges, label allowlists, and absence of high-cardinality labels.

## Audit

Verify audit remains present when traces are unsampled.

---

# 32. Architecture Tests

ArchUnit verifies:

```text
Domain does not depend on Spring
Domain does not depend on JPA
Application does not depend on infrastructure implementations
Controllers do not access repositories
Schedulers do not access JPA repositories directly
Event contract DTOs do not enter the Domain
JPA entities are not returned by APIs
```

---

# 33. End-to-End Golden Path

Environment:

```text
Ticket Workflow
PostgreSQL
RabbitMQ
Keycloak
Stub Agent Runtime
Stub Approval Service
Stub Tool Gateway
Stub Verification Service
```

Path:

```text
Create
→ Triage
→ Classify
→ Investigate
→ Request Approval
→ Grant
→ Execute
→ Verify
→ Resolve
→ Close
```

Assertions cover every status, history record, event, version, audit record, trace, SLA effect, and final resolution.

---

# 34. Alternative E2E Paths

At minimum:

```text
WAITING_FOR_USER → user reply
approval rejected
approval expired
known-safe tool failure
unknown tool result
verification failure and retry
third verification failure escalates
requester cancellation
reopen resolved Ticket
reopen closed Ticket within window
reopen after window rejected
human fix → verification → resolve
```

---

# 35. Stub Services

Stubs support deterministic scenarios:

```text
APPROVE
REJECT
EXPIRE
TOOL_SUCCESS
TOOL_SAFE_FAILURE
TOOL_UNKNOWN
VERIFY_SUCCESS
VERIFY_FAILURE
VERIFY_CONFLICT
```

A scenario ID or test-control API selects behavior.

Random responses are prohibited in deterministic CI tests.

---

# 36. Chaos Tests

Scenarios:

```text
Broker down
PostgreSQL restart
Publisher crash after publish
Consumer crash after commit
Network delay
Confirm timeout
Duplicate event
Out-of-order event
Keycloak unavailable
OTel Collector unavailable
Disk or pool pressure
```

Verify:

- No partial transaction
- No lost events
- No duplicate business effect
- No blind retry after unknown side effects
- Telemetry failure does not block ordinary business operations
- Audit failure blocks high-risk operations

---

# 37. Performance Tests

Use:

```text
k6
or
Gatling
```

Scenarios:

- Create Ticket
- List requester Tickets
- Get Ticket
- Add Message
- Process events
- Publish Outbox
- Query support queue

MVP targets:

```text
Read p95 < 300ms
Command p95 < 800ms
Command p99 < 2s
Event processing p95 < 500ms
99% Outbox publication < 10s
```

---

# 38. Load Model

Recommended traffic mix:

```text
70% reads
20% create or message
10% state-changing commands
```

A separate hotspot scenario validates locking behavior on one Ticket.

---

# 39. Soak Tests

Duration:

```text
2–8 hours for demo
24 hours for staging
```

Observe memory, threads, database pools, queues, Outbox, log volume, trace export, scheduler drift, and retry accumulation.

---

# 40. Dynamic Security Tests

At minimum:

- IDOR
- Scope bypass
- JWT audience confusion
- Stored XSS
- SQL injection
- Log injection
- Prompt injection
- Approval replay
- Event producer spoofing
- Attachment malware
- Recovery replay abuse

---

# 41. Test-data Strategy

Fixtures:

```text
TicketFixtures
TicketBuilder
EventFixtures
PrincipalFixtures
ApprovalFixtures
ToolExecutionFixtures
VerificationFixtures
```

Use:

```text
deterministic UUID
fixed clock
stable EventId
stable CommandId
```

Each integration test uses transaction rollback, an isolated schema, cleanup SQL, or a fresh container.

---

# 42. Fixture Naming

Use business-state names:

```text
investigatingTicket()
waitingForApprovalTicket()
executingTicket()
verifyingTicketWithAttempt(2)
resolvedTicket()
closedTicketWithinReopenWindow()
```

Avoid `ticket1()` and `dummyTicket()`.

---

# 43. Clock Strategy

All time-based behavior uses an injected clock.

Cover:

- Approval expiry
- Reopen window
- 72-hour auto-close
- SLA pause and resume
- Outbox retry
- Lock timeout
- Idempotency stale threshold

Do not use `Thread.sleep()` for business-time logic.

---

# 44. Property-based Testing

Possible tools:

```text
jqwik
QuickTheories
```

Good targets:

- Value objects
- State-transition sequences
- Canonical JSON hashing
- Cursor pagination
- Event ordering

Random tests always report the failing seed.

---

# 45. Property-based State-machine Tests

Generate legal and illegal action sequences.

Continuously assert:

```text
Terminal state does not advance illegally
Version never decreases
Only one active workflow exists
Resolution always has verification
Reopen changes the cycle
```

---

# 46. Mutation Testing

Use:

```text
PIT
```

Focus on:

```text
ticket.domain
ticket.application
```

Recommended:

```text
Domain mutation score >= 80%
Application mutation score >= 70%
```

---

# 47. Code Coverage

Recommended gates:

```text
Overall line >= 80%
Overall branch >= 70%
Domain line >= 90%
Domain branch >= 85%
Application line >= 85%
Application branch >= 75%
```

Tests without meaningful assertions are rejected.

---

# 48. Contract Coverage

Require 100% coverage of:

- Public API operations
- Internal API operations
- Published event types
- Consumed event types
- Stable error codes
- Stable enum values

---

# 49. Test Tags

```text
unit
application
component
integration
contract
security
concurrency
e2e
chaos
performance
```

---

# 50. CI Pipeline

## Stage 1: Fast Verify

```text
compile
format
static analysis
secret scan
unit
ArchUnit
JSON and OpenAPI lint
```

Target: under five minutes.

## Stage 2: Component

```text
application
web slice
security slice
mapper
```

## Stage 3: Infrastructure Integration

```text
PostgreSQL
RabbitMQ
Flyway
Keycloak
Outbox
```

## Stage 4: Contract

```text
OpenAPI diff
Event Schema
Consumer Contract
Producer Contract
```

## Stage 5: Concurrency and E2E

```text
Race Tests
Golden Path
Alternative Paths
```

## Stage 6: Security

```text
SAST
SCA
Container Scan
ZAP Baseline
```

## Stage 7: Nightly

```text
Chaos
Load
Soak
Mutation
Full E2E
```

---

# 51. Pull-request Gate

Every PR passes:

- Compile
- Unit and application tests
- ArchUnit
- Component tests
- Contract tests
- PostgreSQL integration
- RabbitMQ integration
- Core security tests
- Coverage gates
- Secret and dependency scans

Critical race tests run on every PR.

---

# 52. Main-branch Gate

After merge:

- Build image
- Scan image
- Deploy an ephemeral environment
- Run Golden Path E2E
- Run migration tests
- Run smoke tests
- Publish reports

---

# 53. Release Gate

A release candidate requires:

```text
zero unresolved Critical or High security findings
zero failing contract tests
zero flaky critical-path tests
100% critical-invariant coverage
100% transition coverage
successful Golden Path
successful rollback, Outbox, and duplicate chaos tests
performance targets met
100% audit completeness
```

---

# 54. Test Reports

CI artifacts:

```text
JUnit XML
Coverage HTML
Mutation report
Contract diff
Invariant coverage
State-transition coverage
Security scan
Container scan
Load summary
Chaos summary
```

---

# 55. Flaky-test Policy

- Do not hide flakiness with unlimited retries.
- Assign an owner.
- Fix or isolate within 24–48 hours.
- Critical-path tests cannot remain quarantined.
- Preserve failure seeds and container logs.
- Prefer fixed clocks and Awaitility.

---

# 56. Test Retry Policy

CI may retry an infrastructure-startup failure once.

Allowed:

```text
container pull timeout
ephemeral port failure
CI network interruption
```

Not allowed:

```text
wrong state
duplicate row
missing event
authorization bypass
```

---

# 57. Test Environments

## Local

Docker Compose:

```text
PostgreSQL
RabbitMQ
Keycloak
OTel Collector
```

## CI

Testcontainers.

## Demo and Staging

Full deployment for E2E, load, and chaos.

---

# 58. Testcontainers Policy

CI does not share mutable container state.

Local reuse is allowed for speed, but tests remain independent.

Image versions are pinned; `latest` is avoided.

---

# 59. Production Smoke Test

After deployment, run only low-risk checks:

- Health
- Readiness
- Database connectivity
- RabbitMQ connectivity
- Token validation
- Synthetic Ticket
- Outbox publication
- Consumer processing
- Cleanup

Do not execute a real-user tool action.

---

# 60. Synthetic Monitoring

Run a safe periodic flow:

```text
Create synthetic Ticket
→ classify
→ safe no-op action
→ verify
→ close
```

Synthetic Tickets use a dedicated requester and queue and do not enter real KPIs.

---

# 61. Bug-regression Template

Every bug-fix pull request records:

```text
Bug ID
Root cause
Failing scenario
Regression test
Affected invariant, transition, or use case
```

---

# 62. Test Ownership

| Area | Owner |
|---|---|
| Domain and Application | Feature developer |
| API Contract | Backend and Frontend |
| Event Contract | Producer and Consumer |
| Security | Backend and Security reviewer |
| Chaos and Reliability | Backend or Platform |
| Load | Backend or Platform |
| E2E | Feature team |
| Audit | Backend and Security or Compliance |

---

# 63. Definition of Done

A use case is complete only when it includes:

```text
Domain test
Application test
Authorization test
API or event contract test
Persistence or messaging integration test
Error-path test
Idempotency test when applicable
Concurrency test when applicable
Audit test when high-risk
Trace and metric test
Documentation update
```

---

# 64. Minimum MVP Test Set

## Domain

- Create
- Triage
- Waiting for user
- Waiting for approval
- Execute
- Verify
- Resolve
- Close
- Cancel
- Reopen
- Escalate

## Application

- Create Ticket
- Add Message
- Approval Granted
- Tool Success
- Verification Success
- Cancel
- Reopen

## Infrastructure

- PostgreSQL and Flyway
- Optimistic locking
- Transaction and Outbox
- RabbitMQ publish and consume
- Processed Event duplicate handling

## Security

- Own Ticket
- Other user denied
- Support queue
- Wrong service token
- Wrong event producer

## E2E

- Golden Path
- User reply
- Approval rejected
- Unknown tool result
- Verification failure
- Reopen
- Cancel

---

# 65. Rejected Approaches

- Only `@SpringBootTest`
- Mock everything
- H2 instead of PostgreSQL
- Happy Path only
- `Thread.sleep()` for business-time logic
- Test-order dependence
- Retries that hide flakiness
- Coverage as the only quality signal

---

# 66. Acceptance Criteria

- [x] Test pyramid and levels defined
- [x] Business-invariant coverage defined
- [x] State-machine transition coverage defined
- [x] Domain and application tests defined
- [x] API controller and OpenAPI contract tests defined
- [x] Event and consumer contract tests defined
- [x] PostgreSQL, Flyway, and constraint tests defined
- [x] RabbitMQ, Outbox, redelivery, confirm, and DLQ tests defined
- [x] API and event idempotency tests defined
- [x] Security, Keycloak, visibility, and redaction tests defined
- [x] Concurrency and race matrix defined
- [x] Scheduler, reconciliation, audit, and observability tests defined
- [x] Golden Path, alternative path, chaos, and performance tests defined
- [x] Test data, clock, property-based, and mutation tests defined
- [x] Coverage gates, contract coverage, and test tags defined
- [x] CI, PR, main-branch, and release gates defined
- [x] Flaky tests, smoke tests, synthetic monitoring, and ownership defined
- [x] Minimum MVP test set defined

---

# 67. Ticket Workflow LLD Completion

Completed:

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

Next phase:

```text
Implementation Planning
+
Spring Boot Project Skeleton
+
Flyway Migrations
+
Domain Model Coding
```
