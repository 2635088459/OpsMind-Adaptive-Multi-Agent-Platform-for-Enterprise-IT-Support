# SPEC-TW-008 — Assign Ticket

> Domain: Ticket Workflow  
> Service: `ticket-workflow-service`  
> Phase: 03 — Ticket Lifecycle and Ownership  
> Status: Ready for implementation  
> Prerequisites: `SPEC-TW-001` through `SPEC-TW-007`

## 1. Objective

Provide authorized support users and automation agents with safe commands to assign, reassign, and unassign ticket ownership.

This is a command-side vertical slice. A successful command atomically updates the Ticket aggregate and writes assignment history, status history when applicable, timeline, audit, idempotency, and transactional outbox records.

## 2. Business Outcome

The system can always answer:

- who currently owns the ticket;
- who changed ownership, when, and why;
- whether the assignee was eligible for the ticket queue;
- whether the change was an initial assignment, reassignment, or unassignment;
- whether a repeated command has already been processed.

## 3. In Scope

- initial assignment of a `TRIAGED` ticket;
- reassignment without changing the current work status;
- unassignment of an `ASSIGNED` ticket back to `TRIAGED`;
- active-user, tenant, support-role, and queue-membership validation;
- RBAC and queue-level authorization for the acting principal;
- `If-Match` optimistic concurrency;
- `Idempotency-Key` replay protection;
- assignment/history/timeline/audit/outbox persistence;
- `ticket.assigned.v1`, `ticket.reassigned.v1`, and `ticket.unassigned.v1`.

## 4. Out of Scope

- automatic routing or load balancing;
- schedule, capacity, presence, or skill scoring;
- starting work (`SPEC-TW-009`);
- resolving, closing, or reopening tickets (`SPEC-TW-010/011`);
- notifications, SLA escalation, and approval workflow;
- changing the support queue; re-triage owns queue changes.

## 5. State Rules

| Command | Required state | Result state |
|---|---|---|
| Assign | `TRIAGED`, no assignee | `ASSIGNED` |
| Reassign | `ASSIGNED`, `IN_PROGRESS`, `WAITING_FOR_USER`, or `WAITING_FOR_APPROVAL` | unchanged |
| Unassign | `ASSIGNED` | `TRIAGED` |

Unassigning an `IN_PROGRESS` or waiting ticket is rejected. The ticket must first return to an assignable workflow state through `SPEC-TW-009`.

## 6. HTTP API

```text
POST /api/v1/tickets/{ticketId}/assign
POST /api/v1/tickets/{ticketId}/reassign
POST /api/v1/tickets/{ticketId}/unassign
```

All commands require:

```http
If-Match: "<ticket-version>"
Idempotency-Key: <unique-key>
```

## 7. Transaction Boundary

One database transaction must write:

1. ticket ownership and version;
2. assignment history;
3. status history when status changes;
4. requester-safe timeline entry;
5. internal audit entry;
6. idempotency result;
7. outbox event.

Any failure rolls back the entire command.

## 8. Stable Error Codes

`TICKET_NOT_FOUND`, `INVALID_TICKET_STATE`, `TICKET_ALREADY_ASSIGNED`, `TICKET_NOT_ASSIGNED`, `ASSIGNEE_NOT_FOUND`, `ASSIGNEE_INACTIVE`, `ASSIGNEE_NOT_SUPPORT_AGENT`, `ASSIGNEE_NOT_IN_QUEUE`, `FORBIDDEN`, `QUEUE_ACCESS_DENIED`, `VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `VALIDATION_ERROR`.

## 9. Deliverables

- bilingual requirements and implementation documents;
- OpenAPI and AsyncAPI contracts;
- Flyway reference migration;
- executable HTTP examples;
- unit, application, persistence, contract, security, concurrency, and end-to-end tests.

## 10. Definition of Done

- all acceptance criteria pass;
- unauthorized or ineligible assignments produce no writes;
- retries return the original result without duplicate side effects;
- concurrent stale updates are rejected;
- every successful ownership change is traceable;
- API and event payloads validate against the supplied contracts.
