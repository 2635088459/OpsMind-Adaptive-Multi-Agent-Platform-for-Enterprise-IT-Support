# OpsMind Ticket Workflow — 02 Business Invariants

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Business Invariants  
> **Version:** 1.0  
> **Status:** Proposed  
> **Dependency:** `01-domain-model_EN.md`  
> **Recommended Path:** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/02-business-invariants_EN.md`

---

## 1. Purpose

This document defines the business invariants that no Ticket Workflow implementation may violate.

The invariants constrain:

- Ticket Aggregate
- TicketMessage Aggregate
- TicketSla Aggregate
- Application Services
- API command handlers
- RabbitMQ consumers
- Transaction boundaries
- Security checks
- Idempotency
- State-machine design
- Unit and integration tests
- Failure recovery

The complete transition matrix is defined later in `03-state-machine_EN.md`.

---

# 2. Definition of an Invariant

A business invariant is:

> A rule that must remain true regardless of whether a change originates from the frontend, an internal API, a RabbitMQ event, a scheduled job, a human operator, or Agent Runtime.

Example:

```text
A Ticket cannot enter RESOLVED before verification succeeds.
```

This rule cannot live only in the frontend or depend on agent behavior. It must be enforced by the Ticket Domain.

---

# 3. Enforcement Layers

## 3.1 Domain Invariant

Enforced by an aggregate or a pure domain policy.

Example:

```text
A CANCELLED Ticket cannot enter EXECUTING.
```

## 3.2 Application Invariant

Coordinates multiple aggregates, repositories, or external references.

Example:

```text
A user reply and the related Ticket transition are persisted in one business transaction.
```

## 3.3 Security Invariant

Enforced by authentication and authorization.

Example:

```text
An employee may reopen only their own Ticket.
```

## 3.4 Integration Invariant

Enforced by consumers, outbox, ordering, and idempotency.

Example:

```text
A duplicate approval.granted event does not advance a Ticket twice.
```

## 3.5 Persistence Invariant

Supported by database constraints, unique indexes, and optimistic locking.

Example:

```text
A consumer_name and event_id pair is processed once.
```

---

# 4. Error Handling

Invariant violations never silently continue.

Recommended error codes:

```text
INVALID_TICKET_STATE
INVALID_STATE_TRANSITION
ACTIVE_WORKFLOW_ALREADY_EXISTS
WORKFLOW_REFERENCE_MISMATCH
APPROVAL_REFERENCE_MISMATCH
ACTION_REFERENCE_MISMATCH
VERIFICATION_REQUIRED
VERIFICATION_REFERENCE_MISMATCH
TICKET_ALREADY_CANCELLED
TICKET_ALREADY_CLOSED
REOPEN_NOT_ALLOWED
CANCELLATION_NOT_ALLOWED
CONCURRENT_UPDATE
DUPLICATE_COMMAND
STALE_EVENT
OUT_OF_ORDER_EVENT
FORBIDDEN
```

---

# 5. Ticket Identity Invariants

## BI-001 Ticket Has a Unique Internal ID

```text
Ticket.id != null
```

Requirements:

- UUID or ULID
- Globally unique
- Cross-service references do not use sequential database IDs

Enforcement:

```text
Domain + Persistence
```

## BI-002 Ticket Has a Unique Display ID

Example:

```text
INC-2048
```

Requirements:

- Human-readable
- Unique in Ticket Workflow
- Immutable
- Separate from internal TicketId

Enforcement:

```text
Domain + Unique Constraint
```

## BI-003 Ticket Has a Requester

```text
requesterId != null
```

Rules:

- RequesterId is immutable.
- Account deletion does not delete historical Tickets.
- Ticket Domain does not store a complete user profile.

## BI-004 Creation Time Is Required and Immutable

```text
createdAt != null
```

Rules:

- Stored in UTC
- Immutable
- Later timestamps cannot precede createdAt

---

# 6. Ticket Content Invariants

## BI-005 Title Is Valid

```text
trimmed
1–200 characters
not blank
no control characters
```

## BI-006 Initial Description Is Valid

```text
1–10000 characters
not blank
sanitized before display
classified as Sensitive
```

## BI-007 ApplicationCode Comes from an Allowed Set

MVP:

```text
HOUSING_PORTAL
EMAIL
VPN
OTHER
```

Unknown raw values never enter the Domain.

## BI-008 Category and Subcategory Match

Valid:

```text
IDENTITY_ACCESS
├── MFA_FAILURE
├── ACCOUNT_LOCKED
├── GROUP_MEMBERSHIP
└── SESSION_FAILURE
```

Invalid:

```text
category = NETWORK
subcategory = MFA_FAILURE
```

## BI-009 Category Changes Preserve History

Every change records:

```text
oldCategory
newCategory
reason
source
changedAt
```

---

# 7. Lifecycle Invariants

## BI-010 Status Changes Only Through Domain Behavior

Forbidden:

```java
ticket.setStatus(...)
```

Required behaviors include:

```text
startTriaging()
startInvestigation()
waitForApproval()
startVerification()
resolve()
close()
cancel()
reopen()
escalate()
```

## BI-011 Every Transition Writes Status History

A transition atomically performs:

```text
Update Ticket
+
Insert TicketStatusHistory
+
Insert Outbox Event
```

Any failure rolls back the transaction.

## BI-012 History fromStatus Matches Actual Previous State

A history record cannot invent an incorrect previous state.

## BI-013 Core State Changes Increment Aggregate Version

```text
version = version + 1
```

## BI-014 Terminal States Ignore Ordinary Late Events

Terminal states:

```text
CLOSED
CANCELLED
```

Late events such as approval, tool completion, or verification cannot advance them.

## BI-015 CLOSED Cannot Be Automatically Reopened

Reopen is an explicit business action with actor, reason, and timestamp.

## BI-016 FAILED Is Not Implicitly RESOLVED

Failure must lead to retry, investigation, escalation, or explicit cancellation.

---

# 8. Active Workflow Invariants

## BI-017 A Ticket Has at Most One Active Workflow

```text
activeWorkflowId = null
or exactly one WorkflowId
```

## BI-018 A New Workflow Cannot Overwrite an Active Workflow

The current workflow must finish, fail, cancel, or time out and complete business handling first.

## BI-019 Workflow References Match the Ticket

```text
event.ticketId == ticket.id
event.workflowId == ticket.activeWorkflowId
```

Otherwise reject with `WORKFLOW_REFERENCE_MISMATCH`.

## BI-020 CANCELLED Cannot Receive a New Workflow

The Ticket must first be explicitly reopened.

## BI-021 CLOSED Cannot Receive a New Workflow

The Ticket must first be explicitly reopened.

## BI-022 Reopen Creates a New Investigation Path

The MVP recommendation is to create a new WorkflowId after reopen.

---

# 9. User Interaction Invariants

## BI-023 WAITING_FOR_USER Has a Request Reference

Required:

```text
requestId
reasonCode
requestedAt
workflowId
```

## BI-024 User Reply References the Correct Ticket

```text
message.ticketId == ticket.id
```

## BI-025 User Reply Resumes Only a Waiting Ticket

Default:

```text
WAITING_FOR_USER → INVESTIGATING
```

Messages received for CLOSED or CANCELLED Tickets may be stored but do not automatically reopen them.

## BI-026 Messages Are Immutable

Corrections use a new message, redaction marker, or audited visibility change.

## BI-027 Internal Messages Are Not Returned to Requesters

```text
visibility = INTERNAL_SUPPORT_ONLY
```

---

# 10. Pending Action Invariants

## BI-028 The MVP Allows at Most One Pending Action

```text
pendingAction = null
or exactly one PendingActionReference
```

Supporting multiple pending actions requires a future ADR.

## BI-029 Pending Action Belongs to the Active Workflow

```text
pendingAction.workflowId == ticket.activeWorkflowId
```

## BI-030 Pending Action Contains No Credentials

No passwords, tokens, keys, cookies, or private keys.

## BI-031 Action Semantics Cannot Change Under the Same ActionId

Changing from one business action to another requires a new ActionId and new approval.

---

# 11. Approval Invariants

## BI-032 WAITING_FOR_APPROVAL Has an Approval Reference

```text
pendingAction.approvalId != null
```

## BI-033 Approval Matches the Ticket

```text
approval.ticketId == ticket.id
```

## BI-034 Approval Matches the Active Workflow

```text
approval.workflowId == ticket.activeWorkflowId
```

## BI-035 Approval Matches the Pending Action

```text
approval.actionId == pendingAction.actionId
approval.actionType == pendingAction.actionType
```

## BI-036 Approval Is Not Expired

Expired approval cannot advance to EXECUTING.

## BI-037 Rejected Approval Cannot Execute the Same Action

The action is cleared, re-investigated, replaced, or escalated.

## BI-038 Duplicate Approval Granted Is Idempotent

It does not duplicate transitions, history, events, or tool executions.

## BI-039 Approver Respects Separation of Duties

Where policy requires independence, the approver cannot be the requester or a prohibited proposer.

---

# 12. Tool Execution Invariants

## BI-040 EXECUTING Requires Valid Authorization

Approval-required actions need valid approval.

Low-risk actions need an explicit `AUTO_APPROVED` policy decision.

## BI-041 Tool Execution Matches the Pending Action

```text
toolExecution.actionId == pendingAction.actionId
toolExecution.actionType == pendingAction.actionType
```

## BI-042 Tool Execution Matches the Active Workflow

```text
toolExecution.workflowId == activeWorkflowId
```

## BI-043 CANCELLED Cannot Start a New Tool Execution

Late approval events are rejected.

## BI-044 CLOSED Cannot Start Tool Execution

Explicit reopen is required.

## BI-045 Tool Success Never Directly Resolves a Ticket

Allowed:

```text
EXECUTING → VERIFYING
```

Forbidden:

```text
EXECUTING → RESOLVED
```

## BI-046 Unknown Tool Result Enters a Safe State

No blind write retry, no automatic failure, and no automatic resolution.

Use verify-before-retry or escalation.

## BI-047 Write Retries Use a Stable Idempotency Key

The Tool Gateway owns the execution idempotency mechanism.

---

# 13. Verification Invariants

## BI-048 RESOLVED Requires Verification Evidence

```text
verificationId != null
verificationResult == SUCCESS
verifiedAt != null
```

## BI-049 Verification Matches the Ticket

```text
verification.ticketId == ticket.id
```

## BI-050 Verification Matches the Active Workflow

```text
verification.workflowId == ticket.activeWorkflowId
```

## BI-051 Verification Matches the Latest Attempt

Old verification cannot resolve a new investigation cycle.

Match:

```text
attemptId
toolExecutionId or resolutionAttemptId
```

## BI-052 Verification Failure Cannot Resolve the Ticket

It returns to investigation or escalation.

## BI-053 Duplicate Verification Success Is Idempotent

The same VerificationId does not resolve twice.

## BI-054 Verification Is Independent of Resolution Proposal

The same logical step that proposes the resolution cannot be the only verifier.

---

# 14. Resolution and Closure Invariants

## BI-055 Resolution Contains Required Fields

```text
resolutionCode
summary
rootCauseCode
verificationId
resolvedAt
resolvedBy
```

## BI-056 resolvedAt Is Set Once per Resolution Cycle

Reopen creates a new resolution cycle while preserving history.

## BI-057 RESOLVED and CLOSED Are Different

RESOLVED means the issue is believed solved.

CLOSED means the lifecycle is formally complete.

## BI-058 Close Occurs Only from Allowed States

MVP default:

```text
RESOLVED → CLOSED
```

## BI-059 Close Has a Reason, Actor, and Timestamp

## BI-060 CLOSED Core Fields Are Immutable

No changes to requester, lifecycle state, active workflow, pending action, or resolution except through explicit reopen.

---

# 15. Reopen Invariants

## BI-061 Reopen Starts from an Allowed State

Candidate states:

```text
RESOLVED
CLOSED
```

## BI-062 Reopen Requires a Reason

## BI-063 Reopen Requires an Authorized Actor

Requester, IT Support, or an approved system rule.

## BI-064 Reopen Preserves the Previous Resolution

A new cycle uses a new workflow, verification, and resolution.

## BI-065 Reopen Clears Previous Pending Actions

Expired or consumed approvals are not reused.

## BI-066 Reopen Starts a New Investigation

The final state sequence is defined in the state machine.

---

# 16. Cancellation Invariants

## BI-067 Cancellation Has a Reason, Actor, and Timestamp

## BI-068 CLOSED Cannot Be Cancelled

## BI-069 Unknown External Side Effect Prevents Immediate Cancellation

The system verifies the side effect or escalates.

## BI-070 Cancellation Invalidates Pending Actions

Pending approvals and not-yet-started tool actions are invalidated.

## BI-071 Cancellation Terminates or Cancels the Active Workflow

A Ticket cannot remain cancelled while agents continue advancing it.

---

# 17. Escalation Invariants

## BI-072 Escalation Has a Target and Reason

## BI-073 Escalation Preserves Context

Preserve history, findings, tool results, approval results, verification results, and risks.

## BI-074 Escalation Does Not Delete or Hide Failure

The Ticket remains auditable.

## BI-075 Automated Privileged Actions Are Restricted After Escalation

Human authorization is required by default.

---

# 18. Assignment Invariants

## BI-076 A Ticket Has at Most One Current Assignment

Unassigned, assigned to a team, or assigned to a team and support user.

## BI-077 Assignment Has Actor and Timestamp

## BI-078 Assignment History Is Append-Only

## BI-079 Assignment Respects Queue and Role Authorization

---

# 19. SLA Invariants

## BI-080 The MVP Has at Most One Active SLA per Ticket

## BI-081 SLA Deadlines Do Not Precede Ticket Creation

## BI-082 SLA Timer State Follows Ticket-State Policy

Candidate behavior:

```text
WAITING_FOR_USER → PAUSED
WAITING_FOR_APPROVAL → ACTIVE or PAUSED by policy
RESOLVED → MET
CANCELLED → CANCELLED
```

## BI-083 SLA Breach Is Never Silently Reset

Preserve breach time, policy, and reason.

## BI-084 Reopen Defines a New SLA Cycle Strategy

MVP recommendation: create a new SLA cycle record and preserve previous history.

---

# 20. Idempotency Invariants

## BI-085 Create Ticket Supports Idempotency-Key

Repeated requests from the same requester and key return the same result.

## BI-086 The Same Event Is Processed Once

Unique key:

```text
consumer_name + event_id
```

## BI-087 Replay Returns a Stable Result

Replay does not duplicate state changes, events, history, or actions.

## BI-088 The Same Idempotency Key Cannot Represent a Different Payload

Reject with `IDEMPOTENCY_KEY_REUSED`.

---

# 21. Event Ordering Invariants

## BI-089 Aggregate Version Never Moves Backward

Older versions are stale or duplicate.

## BI-090 Events Cannot Skip Required Versions

Gaps trigger delay, retry, reconciliation, or DLQ.

## BI-091 Late Terminal Events Cannot Affect a New Cycle

Old Workflow verification cannot resolve a reopened Ticket.

---

# 22. Concurrency Invariants

## BI-092 Core Ticket Updates Use Expected Version

No unconditional overwrite.

## BI-093 Optimistic-Lock Failure Requires Business Re-evaluation

```text
reload
→ re-evaluate
→ retry or reject
```

## BI-094 Cancel and Approval Granted Produce One Legal Winner

Optimistic locking and business rules determine the result.

---

# 23. Transaction Invariants

## BI-095 Ticket, History, and Outbox Commit Atomically

Any failure rolls back all changes.

## BI-096 Processed Event and Business Update Commit Together

Avoid marking an event processed without applying the business change or vice versa.

## BI-097 Database Transactions Do Not Call External Systems

No RabbitMQ publish, LLM, LangSmith, Tool Gateway, Keycloak Admin API, Okta, or Duo inside the transaction.

---

# 24. Security and PII Invariants

## BI-098 Employees Read Only Their Own Tickets

Unless another authorized role applies.

## BI-099 Auditors Are Read-Only

No lifecycle changes, approvals, tool calls, or ordinary support messages.

## BI-100 Service Identity Does Not Impersonate an Employee

Background services use distinct identities.

## BI-101 Secrets Never Enter Ticket Domain

No passwords, tokens, keys, or private keys.

## BI-102 Integration Events Minimize PII

`ticket.created` does not broadcast the complete description.

## BI-103 LangSmith Metadata Is Redacted

Allowed:

```text
ticket_id
workflow_id
hashed requester id
category
status
```

Forbidden:

```text
raw email
access token
full login log
credential
```

---

# 25. Audit and History Invariants

## BI-104 Audit Is Append-Only

## BI-105 Status History Is Not Deleted by Ordinary Operations

Controlled legal-retention processes must preserve proof.

## BI-106 Critical Actions Store Actor, Reason, and Time

Includes cancel, reopen, close, escalate, assignment, manual transition, and category override.

## BI-107 Timeline Is Not a Business Source of Truth

Timeline is a read model composed from authoritative records.

---

# 26. Observability Invariants

## BI-108 Commands and Events Propagate Trace Context

At minimum:

```text
trace_id
correlation_id
ticket_id
workflow_id
```

## BI-109 Metrics Labels Exclude PII and High-Cardinality IDs

Do not use ticket_id, requester_id, or message body as Prometheus labels.

## BI-110 Telemetry Failure Does Not Block Business

Telemetry is fail-open; security remains fail-closed.

---

# 27. Priority

## Critical

Security, authorization, and external-side-effect safety:

```text
BI-032–047
BI-048–054
BI-067–071
BI-098–103
```

## High

Business correctness:

```text
BI-010–022
BI-055–066
BI-085–097
```

## Medium

Auditability and maintainability:

```text
BI-023–031
BI-072–084
BI-104–110
```

---

# 28. Implementation Mapping

| Invariant Type | Primary Implementation |
|---|---|
| Identity / Required Fields | Value Objects / DB Constraints |
| Lifecycle | Ticket Aggregate |
| Workflow Matching | Ticket Aggregate + Event Consumer |
| Approval Matching | Ticket Aggregate + Policy Integration |
| Tool Matching | Event Consumer + Ticket Aggregate |
| Verification | Resolution Policy + Ticket Aggregate |
| Authorization | Spring Security / Application Service |
| Idempotency | Idempotency Store / Processed Events |
| Concurrency | JPA Version / Optimistic Lock |
| History | Application Transaction |
| PII | DTO, log, and event mapping |
| Observability | Shared OpenTelemetry instrumentation |

---

# 29. Required Tests

Every Critical and High invariant requires automated coverage.

Examples:

```text
shouldRejectExecutionWhenTicketIsCancelled
shouldRejectApprovalForDifferentWorkflow
shouldRejectApprovalForDifferentAction
shouldRequireVerificationBeforeResolution
shouldIgnoreDuplicateVerificationEvent
shouldRejectStaleWorkflowEventAfterReopen
shouldRollbackTicketWhenOutboxInsertFails
shouldRejectDifferentPayloadForSameIdempotencyKey
shouldReevaluateAfterOptimisticLockConflict
```

---

# 30. Relationship to State Machine

`03-state-machine_EN.md` must reference the `BI-xxx` identifiers.

Example:

```text
WAITING_FOR_APPROVAL → EXECUTING
```

At minimum references:

```text
BI-032
BI-033
BI-034
BI-035
BI-036
BI-038
BI-040
BI-043
```

Every transition defines:

- Trigger
- Actor
- Preconditions
- Applicable invariants
- Side effects
- Domain events
- Failure codes
- Idempotency behavior

---

# 31. Questions for the State-Machine Document

1. Is REOPENED a persistent state?
2. Is FAILED terminal or intermediate?
3. Can ESCALATED return to INVESTIGATING?
4. What happens when cancellation occurs after tool execution?
5. Where does Approval Rejected transition?
6. Where does Approval Expired transition?
7. How many verification retries are allowed?
8. What is the auto-close duration?
9. Does WAITING_FOR_APPROVAL pause SLA?
10. Does reopen create a new SLA cycle?

---

# 32. Acceptance Criteria

- [x] Identity invariants defined
- [x] Content invariants defined
- [x] Lifecycle invariants defined
- [x] Active Workflow invariants defined
- [x] Message invariants defined
- [x] Pending Action invariants defined
- [x] Approval invariants defined
- [x] Tool Execution invariants defined
- [x] Verification invariants defined
- [x] Resolution and closure invariants defined
- [x] Reopen and cancellation invariants defined
- [x] Escalation, assignment, and SLA invariants defined
- [x] Idempotency, ordering, and concurrency invariants defined
- [x] Transaction, security, audit, and observability invariants defined
- [x] Test mapping defined
- [ ] Final transitions will be frozen in `03-state-machine_EN.md`

---

# 33. Next Step

Create:

```text
03-state-machine_CN.md
03-state-machine_EN.md
```

The state machine must reference the `BI-xxx` identifiers defined here.
