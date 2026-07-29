# Phase 03 — Ticket Lifecycle and Ownership

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 03
>
> Specs: `SPEC-TW-007` through `SPEC-TW-011`
>
> Prerequisite: Phase 01 and Phase 02 are implemented and accepted
>
> Document Status: Implementation Plan

## 1. Phase Objective

Phase 01 introduced ticket creation. Phase 02 introduced ticket retrieval, requester ticket lists, ticket messages, support queue queries, and ticket timelines.

Phase 03 completes the command side of Ticket Workflow so that support personnel and automation agents can safely:

- triage a ticket;
- assign, reassign, or unassign an owner;
- advance a ticket through an explicit state machine;
- store a structured resolution and mark a ticket as resolved;
- close a ticket or reopen it when the issue returns;
- produce consistent timeline entries, audit records, and domain events for every write;
- prevent duplicate execution and concurrent overwrites through idempotency and optimistic locking.

After Phase 03, the system must demonstrate the complete lifecycle of a ticket from creation, triage, assignment, active work, waiting, resolution, and closure through reopening.

## 2. Business Value

Without lifecycle and ownership controls, a ticket is only a queryable record. It cannot function as an executable and traceable IT support work item.

Phase 03 provides:

- clear ownership for every active ticket;
- protection against invalid or unauthorized state changes;
- complete ownership and status histories;
- reliable state for future SLA, approval, notification, and agent-remediation features;
- shared workflow rules for human support agents and AI agents;
- verifiable and auditable command boundaries for later automation.

## 3. Scope

### 3.1 In Scope

- Ticket category, subcategory, priority, and support queue;
- triage actor and timestamp;
- assignment, reassignment, and unassignment;
- the ticket state machine and transition validation;
- waiting-for-user and waiting-for-approval states;
- resolution summary, resolution code, and resolution cycles;
- closure and reopening;
- ownership history, status history, and ticket timeline;
- audit logging;
- transactional outbox domain events;
- idempotency;
- optimistic locking and version checks;
- RBAC, input validation, error contracts, and observability;
- unit, integration, contract, and end-to-end tests.

### 3.2 Out of Scope

- Automatic classification or priority models;
- SLA timers and breach escalation;
- the complete approval-request workflow;
- email, Slack, or mobile notifications;
- agent tool execution and automatic remediation;
- knowledge retrieval and resolution recommendations;
- cross-ticket Problem, Incident, or Change Management;
- reporting and analytics dashboards.

These capabilities may consume the states and events produced by Phase 03, but they are not implemented in this phase.

## 4. Actors and Authorization

| Actor | Primary Permissions |
|---|---|
| Requester | View owned tickets and timelines; cannot triage, assign, resolve, or close |
| Support Agent | Triage, claim, advance, and resolve tickets within authorized queues |
| Support Lead | Assign across authorized queues, reassign, unassign, close, and reopen |
| Automation Agent | Execute only explicitly granted operations through a service identity |
| System | Write timeline, audit, history, and outbox records |

Authorization must be enforced on the server and must not depend on hidden frontend controls.

## 5. Unified Lifecycle Model

### 5.1 Persisted Statuses

```text
OPEN
TRIAGED
ASSIGNED
IN_PROGRESS
WAITING_FOR_USER
WAITING_FOR_APPROVAL
RESOLVED
CLOSED
```

`REOPENED` is not a long-lived persisted status. Reopening is a business operation and a domain event. A successfully reopened ticket enters `IN_PROGRESS` and starts a new resolution cycle. This avoids adding a transient-only state to queries and state-machine rules.

### 5.2 Primary Flow

```text
OPEN
  → TRIAGED
  → ASSIGNED
  → IN_PROGRESS
  → WAITING_FOR_USER / WAITING_FOR_APPROVAL
  → IN_PROGRESS
  → RESOLVED
  → CLOSED
```

Reopening:

```text
RESOLVED → IN_PROGRESS
CLOSED   → IN_PROGRESS
```

### 5.3 Transition Matrix

| Current Status | Allowed Target Status | Triggering Operation |
|---|---|---|
| `OPEN` | `TRIAGED` | Triage |
| `TRIAGED` | `ASSIGNED` | Assign |
| `ASSIGNED` | `TRIAGED` | Unassign |
| `ASSIGNED` | `IN_PROGRESS` | Start Work |
| `IN_PROGRESS` | `WAITING_FOR_USER` | Wait for User |
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | Wait for Approval |
| `IN_PROGRESS` | `RESOLVED` | Resolve |
| `WAITING_FOR_USER` | `IN_PROGRESS` | Resume Work |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Resume Work |
| `RESOLVED` | `CLOSED` | Close |
| `RESOLVED` | `IN_PROGRESS` | Reopen |
| `CLOSED` | `IN_PROGRESS` | Reopen |

Any transition not listed in the matrix is invalid by default. Examples include:

- `OPEN → RESOLVED`
- `TRIAGED → CLOSED`
- `CLOSED → WAITING_FOR_USER`
- `WAITING_FOR_APPROVAL → RESOLVED`

## 6. The Five Phase 03 Specs

| Order | Spec | Name | Core Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-007` | Triage Ticket | Set classification, priority, and support queue and complete initial triage |
| 2 | `SPEC-TW-008` | Assign Ticket | Assign, reassign, or unassign the ticket owner |
| 3 | `SPEC-TW-009` | Transition Ticket Status | Execute general status transitions and reject invalid transitions |
| 4 | `SPEC-TW-010` | Resolve Ticket | Store the resolution and complete the current resolution cycle |
| 5 | `SPEC-TW-011` | Close and Reopen Ticket | Close a resolved ticket or create a new work cycle |

## 7. SPEC-TW-007 — Triage Ticket

### 7.1 Goal

Convert an `OPEN` ticket into a `TRIAGED` ticket that has received an initial assessment, belongs to a support queue, and is ready for assignment.

### 7.2 Required Behavior

- Set `category`;
- optionally set `subcategory`;
- set `priority`;
- set `supportQueueId`;
- store `triagedBy` and `triagedAt`;
- validate the category, priority, and queue;
- verify that the actor is authorized for the target queue;
- allow only `OPEN → TRIAGED`;
- update the timeline, audit log, and ticket version;
- write the outbox event in the same transaction.

### 7.3 Domain Event

```text
ticket.triaged.v1
```

### 7.4 Outcome

The ticket has an explicit business classification, priority, and support queue and is ready for ownership assignment.

## 8. SPEC-TW-008 — Assign Ticket

### 8.1 Goal

Establish traceable ownership for a ticket and support ownership changes.

### 8.2 Required Behavior

- Assign a `TRIAGED` ticket to a valid Support Agent;
- perform `TRIAGED → ASSIGNED` on first assignment;
- reassign the owner without changing the current work status;
- allow authorized roles to unassign a ticket;
- return an `ASSIGNED` ticket to `TRIAGED` when it is unassigned;
- reject unassignment from `IN_PROGRESS` or a waiting state unless a valid workflow operation first returns it to an assignable state;
- verify that the assignee exists, is active, and is authorized for the queue;
- store ownership history with the previous owner, new owner, reason, actor, and timestamp;
- support `expectedVersion` or `If-Match` optimistic locking;
- update the timeline, audit log, and outbox.

### 8.3 Domain Events

```text
ticket.assigned.v1
ticket.reassigned.v1
ticket.unassigned.v1
```

## 9. SPEC-TW-009 — Transition Ticket Status

### 9.1 Goal

Provide a state-machine-constrained command for advancing tickets through active-work and waiting states.

### 9.2 Supported Transitions

- `ASSIGNED → IN_PROGRESS`
- `IN_PROGRESS → WAITING_FOR_USER`
- `IN_PROGRESS → WAITING_FOR_APPROVAL`
- `WAITING_FOR_USER → IN_PROGRESS`
- `WAITING_FOR_APPROVAL → IN_PROGRESS`

Triage, assignment, resolution, closure, and reopening must continue to use their dedicated commands. The general transition endpoint must not bypass their business rules.

### 9.3 Required Behavior

- Maintain transition rules in one central source;
- validate the current and target statuses;
- require a transition `reason`;
- optionally store `waitingForRequesterSince` when waiting for the requester;
- optionally store `approvalReference` when waiting for approval;
- clear the relevant waiting metadata when work resumes;
- require a ticket owner before entering `IN_PROGRESS`;
- reject direct transitions to `RESOLVED` or `CLOSED`;
- store status history;
- support idempotency and optimistic locking;
- update the timeline, audit log, and outbox.

### 9.4 Domain Event

```text
ticket.status-changed.v1
```

## 10. SPEC-TW-010 — Resolve Ticket

### 10.1 Goal

Model resolution as a dedicated operation containing structured business data instead of as a generic status update.

### 10.2 Required Behavior

- Allow only `IN_PROGRESS → RESOLVED`;
- require a non-empty `resolutionSummary`;
- require a valid `resolutionCode`;
- store `resolvedBy` and `resolvedAt`;
- complete the current resolution cycle;
- clear waiting metadata;
- retain the current owner for review and later closure;
- return the original successful result for repeated requests with the same idempotency key;
- update the timeline, status history, audit log, and outbox.

### 10.3 Recommended Resolution Codes

```text
FIXED
WORKAROUND_PROVIDED
DUPLICATE
REQUEST_FULFILLED
NOT_REPRODUCIBLE
USER_ERROR
NO_ACTION_REQUIRED
```

### 10.4 Domain Event

```text
ticket.resolved.v1
```

## 11. SPEC-TW-011 — Close and Reopen Ticket

### 11.1 Close

- Allow only `RESOLVED → CLOSED`;
- store `closedBy`, `closedAt`, and `closeReason`;
- reject general status transitions after closure;
- update the timeline, status history, audit log, and outbox.

### 11.2 Reopen

- Allow `RESOLVED → IN_PROGRESS`;
- allow `CLOSED → IN_PROGRESS`;
- require a non-empty `reopenReason`;
- start a new resolution cycle;
- clear current resolution fields from the previous cycle while retaining its historical snapshot;
- retain the previous owner; if that owner is no longer active, require reassignment before active work can begin;
- increment the reopen count;
- update the timeline, status history, audit log, and outbox.

### 11.3 Domain Events

```text
ticket.closed.v1
ticket.reopened.v1
```

## 12. API Command Boundaries

Recommended HTTP endpoints:

```text
POST /api/v1/tickets/{ticketId}/triage
POST /api/v1/tickets/{ticketId}/assignments
POST /api/v1/tickets/{ticketId}/reassignments
DELETE /api/v1/tickets/{ticketId}/assignment
POST /api/v1/tickets/{ticketId}/status-transitions
POST /api/v1/tickets/{ticketId}/resolution
POST /api/v1/tickets/{ticketId}/closure
POST /api/v1/tickets/{ticketId}/reopen
```

Every write request should support:

```text
Authorization: Bearer <token>
Idempotency-Key: <uuid>
If-Match: "<ticket-version>"
X-Correlation-Id: <uuid>
```

Recommended successful responses:

```text
200 OK      Update an existing ticket
201 Created Create an assignment, resolution cycle, or another subordinate resource
```

Recommended errors:

```text
400 INVALID_REQUEST
401 UNAUTHENTICATED
403 FORBIDDEN
404 TICKET_NOT_FOUND
409 INVALID_STATUS_TRANSITION
409 IDEMPOTENCY_CONFLICT
412 VERSION_MISMATCH
422 BUSINESS_RULE_VIOLATION
```

## 13. Data Model Changes

### 13.1 New or Confirmed Ticket Aggregate Fields

```text
status
category
subcategory
priority
support_queue_id
assignee_id
triaged_by
triaged_at
resolved_by
resolved_at
closed_by
closed_at
reopen_count
version
updated_at
```

### 13.2 New History Entities

```text
ticket_assignment_history
ticket_status_history
ticket_resolution_cycle
```

Every history record should include at least:

- `ticketId`
- before and after values
- `reason`
- `actorType`
- `actorId`
- `occurredAt`
- `correlationId`

## 14. Transactions and Consistency

Every command must complete the following work in one local database transaction:

1. Load the ticket and its current version;
2. validate authorization and business rules;
3. modify the ticket aggregate;
4. write the corresponding history;
5. write the timeline entry;
6. write the audit record;
7. write the outbox event;
8. commit the transaction.

An event-publication failure must not roll back an already committed business transaction. The outbox publisher retries publication after the transaction commits.

## 15. Idempotency and Concurrency Control

- Every command endpoint uses `Idempotency-Key`;
- the same key with the same request returns the original result;
- the same key with a different payload returns `409 IDEMPOTENCY_CONFLICT`;
- the Ticket aggregate uses a monotonically increasing `version`;
- the client submits the expected version through `If-Match` or `expectedVersion`;
- a version mismatch returns `412 VERSION_MISMATCH`;
- a successful write returns the new `ETag`;
- last-write-wins must never silently overwrite ownership or status.

## 16. Shared Timeline, Audit, and Event Standards

Every successful command must produce:

- a requester-visible or support-visible timeline entry;
- an immutable audit record;
- the corresponding domain event;
- a correlation ID;
- the actor identity;
- a before/after snapshot or the required differences;
- a server-generated timestamp.

A failed command must not produce a business-success event, although security-related failures may be written to the security audit log.

## 17. Security Requirements

- Derive the actor identity from the authentication token; do not accept it from the request body;
- enforce both RBAC and queue-level authorization;
- require Automation Agents to use dedicated service identities;
- apply length limits and safe handling to free-text fields such as resolution summaries and reasons;
- never place access tokens, passwords, or other secrets in logs or events;
- apply stricter authorization to cross-queue operations, closure, and reopening;
- return stable and testable error codes for every rejection.

## 18. Observability

### 18.1 Metrics

```text
ticket_triage_total
ticket_assignment_total
ticket_status_transition_total
ticket_resolution_total
ticket_closure_total
ticket_reopen_total
ticket_command_failure_total
ticket_version_conflict_total
ticket_command_duration_seconds
```

### 18.2 Structured Logs

Include at least:

```text
ticketId
commandName
actorId
actorType
fromStatus
toStatus
result
errorCode
correlationId
durationMs
```

### 18.3 Tracing

The HTTP command, database transaction, and outbox publication should share one trace and correlation context.

## 19. Test Strategy

### 19.1 Unit Tests

- Every legal status transition;
- every illegal status transition;
- triage field validation;
- assignee and queue authorization;
- resolve, close, and reopen rules;
- resolution-cycle creation and completion;
- idempotency rules;
- optimistic locking.

### 19.2 Integration Tests

- Complete API → application → domain → persistence path;
- atomic commit of the ticket, history, timeline, audit, and outbox;
- no partial data after transaction rollback;
- only one winner for concurrent updates;
- RBAC and queue authorization;
- outbox publisher retries.

### 19.3 Contract Tests

- Request and response schemas;
- stable error codes;
- event names, versions, and payloads;
- `ETag`, `If-Match`, and `Idempotency-Key` behavior.

### 19.4 End-to-End Scenario

```text
Create Ticket
→ Get Ticket
→ Triage
→ Assign
→ Start Work
→ Wait for User
→ Resume Work
→ Resolve
→ Close
→ Reopen
→ Resolve Again
→ Close Again
→ Verify Timeline and History
```

## 20. Implementation Order

```text
SPEC-TW-007 Triage Ticket
        ↓
SPEC-TW-008 Assign Ticket
        ↓
SPEC-TW-009 Transition Ticket Status
        ↓
SPEC-TW-010 Resolve Ticket
        ↓
SPEC-TW-011 Close and Reopen Ticket
```

Complete each spec as an independent vertical slice:

```text
README / Acceptance Criteria
→ API Contract
→ Domain Rules
→ Persistence Migration
→ Application Handler
→ Timeline / Audit / Outbox
→ Tests
→ Documentation
```

Do not begin the next spec until all acceptance criteria for the current spec pass.

## 21. Phase Exit Criteria

Phase 03 can be marked complete only when:

- `SPEC-TW-007` through `SPEC-TW-011` are implemented;
- the state machine has one authoritative source of rules;
- every invalid transition is rejected;
- ownership and status histories are queryable and agree with current ticket state;
- resolution, closure, and reopening use dedicated business commands;
- every write supports idempotency and optimistic locking;
- timeline, audit, and outbox records are written in the same transaction;
- RBAC and queue-level authorization tests pass;
- unit, integration, contract, and end-to-end tests pass;
- API and event documentation is complete;
- the demonstration script completes two resolution cycles.

## 22. Recommended Directory Structure

```text
docs/
├── implementation-plans/
│   └── domains/
│       └── 02-ticket-workflow/
│           ├── phase-03-ticket-lifecycle-and-ownership_CN.md
│           └── phase-03-ticket-lifecycle-and-ownership_EN.md
└── specs/
    └── ticket-workflow/
        ├── SPEC-TW-007-triage-ticket/
        ├── SPEC-TW-008-assign-ticket/
        ├── SPEC-TW-009-transition-ticket-status/
        ├── SPEC-TW-010-resolve-ticket/
        └── SPEC-TW-011-close-and-reopen-ticket/
```

The implementation remains under:

```text
services/ticket-workflow-service/
```

## 23. After Phase 03

After Phase 03, Ticket Workflow has a stable lifecycle foundation. A later phase can introduce SLA, approvals, automation execution, notifications, or agent orchestration without weakening the core state machine.

Phase 03 does not implement these capabilities early, but it must provide reliable inputs through stable event contracts and extension points.
