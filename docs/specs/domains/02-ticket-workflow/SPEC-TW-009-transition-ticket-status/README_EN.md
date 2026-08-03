# SPEC-TW-009 — Transition Ticket Status

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 03 — Ticket Lifecycle and Ownership
>
> Status: Ready for Implementation
>
> Prerequisites: `SPEC-TW-001` through `SPEC-TW-008`

## 1. Goal

Provide an authorized, state-machine-guarded command for moving an owned ticket through work and waiting states.

This SPEC covers only the post-assignment, pre-resolution transitions:

```text
ASSIGNED -> IN_PROGRESS
IN_PROGRESS -> WAITING_FOR_USER
IN_PROGRESS -> WAITING_FOR_APPROVAL
WAITING_FOR_USER -> IN_PROGRESS
WAITING_FOR_APPROVAL -> IN_PROGRESS
```

Triage, assignment, resolution, closure, and reopening remain dedicated commands and must not be bypassed through this generic status endpoint.

## 2. Business Outcome

The system can answer:

- whether work has started;
- whether the ticket is waiting on the requester or approval;
- who changed status, when, and why;
- whether waiting metadata matches the current status;
- whether the same transition command was already processed;
- whether status history, timeline, audit, outbox, and ticket version agree.

## 3. In Scope

- introduce `IN_PROGRESS` as a Phase 03 persisted status;
- execute `ASSIGNED -> IN_PROGRESS`;
- execute `IN_PROGRESS -> WAITING_FOR_USER`;
- execute `IN_PROGRESS -> WAITING_FOR_APPROVAL`;
- resume waiting states back to `IN_PROGRESS`;
- require a `reason` for every transition;
- store `waitingForRequesterSince` for waiting-on-user;
- store `approvalReference` for waiting-on-approval;
- clear waiting metadata when work resumes;
- require an assignee before entering work or waiting states;
- RBAC and queue-level authorization;
- `If-Match` optimistic locking;
- `Idempotency-Key` protection;
- status history, timeline, audit, idempotency, and transactional outbox writes;
- publish `ticket.status-changed.v1`.

## 4. Out of Scope

- triage, assignment, reassignment, or unassignment;
- resolution summary or resolution code;
- close, reopen, or new resolution-cycle creation;
- the approval business workflow itself;
- SLA pause/resume, breach escalation, or notifications;
- agent tool execution, automated remediation, and independent verification;
- legacy frozen-state-machine semantics for `INVESTIGATING`, `EXECUTING`, or `VERIFYING`.

## 5. State Rules

| Current Status | Target Status | Meaning |
|---|---|---|
| `ASSIGNED` | `IN_PROGRESS` | Start Work |
| `IN_PROGRESS` | `WAITING_FOR_USER` | Wait for User |
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | Wait for Approval |
| `WAITING_FOR_USER` | `IN_PROGRESS` | Resume Work |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Resume Work |

Every unlisted transition is invalid by default.

## 6. HTTP API

```text
POST /api/v1/tickets/{ticketId}/status-transitions
```

Every request requires:

```http
If-Match: "<ticket-version>"
Idempotency-Key: <unique-key>
```

## 7. Transaction Boundary

One local database transaction must write:

1. ticket status, waiting metadata, `updated_at`, and version;
2. status history;
3. requester-safe timeline;
4. internal audit;
5. idempotency result;
6. outbox event.

Any write failure rolls back the entire command.

## 8. Stable Error Codes

`TICKET_NOT_FOUND`, `INVALID_STATUS_TRANSITION`, `TICKET_NOT_ASSIGNED`, `FORBIDDEN`, `QUEUE_ACCESS_DENIED`, `VERSION_CONFLICT`, `PRECONDITION_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`, `VALIDATION_ERROR`.

## 9. Deliverables

- Chinese and English requirements and implementation documents;
- OpenAPI and AsyncAPI contracts;
- Flyway reference migration;
- executable HTTP examples;
- Domain, Application, Persistence, Contract, Security, Concurrency, and E2E tests.

## 10. Definition of Done

- all acceptance criteria pass;
- status transition rules have one source of truth;
- invalid transitions produce no writes;
- waiting metadata matches current status;
- retries return the original result without duplicate side effects;
- stale concurrent updates are rejected;
- every successful status change is traceable;
- API and event payloads validate against contracts.
