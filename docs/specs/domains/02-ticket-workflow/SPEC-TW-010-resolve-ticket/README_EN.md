# SPEC-TW-010 — Resolve Ticket

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 03 — Ticket Lifecycle and Ownership
>
> Status: Ready for Implementation
>
> Prerequisites: `SPEC-TW-001` through `SPEC-TW-009`

## 1. Goal

Model ticket resolution as a dedicated command carrying structured business data instead of as a generic status transition.

This SPEC covers only:

```text
IN_PROGRESS -> RESOLVED
```

Closure and reopen belong to `SPEC-TW-011`. General status transitions remain owned by `SPEC-TW-009`.

## 2. Business Outcome

The system can answer:

- who resolved the ticket and when;
- what resolution code and summary were provided;
- whether the current resolution cycle was completed;
- who owned the ticket at resolution time;
- whether the same resolution request was already processed;
- whether ticket, resolution cycle, status history, timeline, audit, and outbox agree.

## 3. In Scope

- allow only `IN_PROGRESS -> RESOLVED`;
- require nonblank `resolutionSummary`;
- require `resolutionCode` from a controlled enum;
- store `resolvedBy` and `resolvedAt`;
- clear waiting metadata;
- retain the current assignee;
- complete the current resolution cycle;
- write status history, timeline, audit, idempotency, and outbox;
- publish `ticket.resolved.v1`;
- support `If-Match` optimistic locking;
- support `Idempotency-Key`;
- RBAC and queue-level authorization.

## 4. Out of Scope

- close ticket;
- reopen ticket;
- auto-close scheduler;
- requester satisfaction confirmation;
- SLA breach calculation;
- notifications;
- approval workflow;
- agent tool execution and verification;
- changing assignee or support queue.

## 5. Resolution Codes

```text
FIXED
WORKAROUND_PROVIDED
DUPLICATE
REQUEST_FULFILLED
NOT_REPRODUCIBLE
USER_ERROR
NO_ACTION_REQUIRED
```

## 6. HTTP API

```text
POST /api/v1/tickets/{ticketId}/resolution
```

Every request requires:

```http
If-Match: "<ticket-version>"
Idempotency-Key: <unique-key>
```

## 7. Transaction Boundary

One local database transaction must write:

1. ticket status, resolution fields, waiting metadata, `updated_at`, and version;
2. current resolution-cycle completion;
3. status history;
4. requester-safe timeline;
5. internal audit;
6. idempotency result;
7. outbox event.

Any write failure rolls back the entire command.

## 8. Stable Error Codes

`TICKET_NOT_FOUND`, `INVALID_STATUS_TRANSITION`, `TICKET_NOT_ASSIGNED`, `RESOLUTION_CODE_INVALID`, `RESOLUTION_CYCLE_NOT_FOUND`, `RESOLUTION_CYCLE_ALREADY_COMPLETED`, `FORBIDDEN`, `QUEUE_ACCESS_DENIED`, `VERSION_CONFLICT`, `PRECONDITION_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`, `VALIDATION_ERROR`.

## 9. Deliverables

- Chinese and English requirements and implementation documents;
- OpenAPI and AsyncAPI contracts;
- Flyway reference migration;
- executable HTTP examples;
- Domain, Application, Persistence, Contract, Security, Concurrency, and E2E tests.

## 10. Definition of Done

- all acceptance criteria pass;
- non-`IN_PROGRESS` tickets cannot be resolved;
- resolution summary/code are persisted and traceable;
- current resolution cycle is completed correctly;
- retries return the original result without duplicate side effects;
- stale concurrent updates are rejected;
- API and event payloads validate against contracts.
