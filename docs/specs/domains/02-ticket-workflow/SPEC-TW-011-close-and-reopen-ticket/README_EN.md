# SPEC-TW-011 — Close and Reopen Ticket

## 1. Goal

`SPEC-TW-011` completes the final lifecycle segment in Phase 03:

- close a resolved ticket from `RESOLVED` to `CLOSED`;
- reopen a `RESOLVED` or `CLOSED` ticket into `IN_PROGRESS`;
- create a new resolution cycle on reopen;
- preserve historical resolution and close snapshots from prior cycles;
- write consistent status history, timeline, audit, outbox, and idempotency records.

This SPEC inherits the command boundary, versioning, authorization, and event delivery model established by `SPEC-TW-007` through `SPEC-TW-010`.

## 2. Authorities

- `docs/implementation-plans/domains/02-ticket-workflow/phase-03-ticket-lifecycle-and-ownership_CN.md`
- `docs/implementation-plans/domains/02-ticket-workflow/phase-03-ticket-lifecycle-and-ownership_EN.md`
- `docs/low-level-design/domains/02-ticket-workflow/03-state-machine/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/06-event-contracts/README_EN.md`
- `docs/low-level-design/domains/02-ticket-workflow/07-data-model/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/09-concurrency-and-idempotency/README_CN.md`

If the earlier low-level state machine conflicts with the Phase 03 implementation plan, Phase 03 wins. Current persistent states are:

```text
OPEN -> TRIAGED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED
```

`REOPENED` is not a persistent state; a successful reopen enters `IN_PROGRESS`.

## 3. Scope

### 3.1 Included

- `POST /api/v1/tickets/{ticketId}/closure`
- `POST /api/v1/tickets/{ticketId}/reopen`
- `RESOLVED -> CLOSED`
- `RESOLVED -> IN_PROGRESS`
- `CLOSED -> IN_PROGRESS`
- `closeReason` / `closedBy` / `closedAt`
- `reopenReason` / `reopenedBy` / `reopenedAt`
- `reopen_count`
- new resolution cycle
- status history, resolution cycle history, timeline, audit, outbox
- `ticket.closed.v1`
- `ticket.reopened.v1`

### 3.2 Excluded

- auto-close scheduler;
- close confirmation UI;
- notification delivery;
- SLA engine recalculation;
- automatic reassignment;
- long-running workflow restart;
- cancel, escalate, or incident/problem/change linkage.

## 4. Close Semantics

Close is a business command that terminates the current resolved cycle. It only allows:

```text
RESOLVED -> CLOSED
```

After success:

- ticket status is `CLOSED`;
- `closedBy`, `closedAt`, and `closeReason` are stored;
- the current resolution cycle becomes `CLOSED`;
- general status-transition APIs can no longer mutate the ticket;
- only the reopen command can move it back into the workflow.

## 5. Reopen Semantics

Reopen means the issue returned, the solution failed, or requester/support decided more work is required. It allows:

```text
RESOLVED -> IN_PROGRESS
CLOSED   -> IN_PROGRESS
```

After success:

- ticket status is `IN_PROGRESS`;
- `reopen_count` increments;
- a new active resolution cycle is created;
- `current_resolution_cycle_id` points to the new cycle;
- current resolution/close fields are cleared;
- historical snapshots remain on prior cycles;
- previous assignee is retained;
- if the previous assignee is inactive, the ticket can reopen, but active work must first reassign or correct ownership.

## 6. Authorization

- Close: Support Lead or authorized Support Agent; Requester cannot call the support close API directly.
- Reopen: Support Lead, authorized Support Agent, or a future requester endpoint by product decision; this SPEC defaults to a support command.
- Automation Agent must use an explicitly granted service identity.
- All authorization is server-side and queue-scoped from the ticket's support queue.

## 7. Idempotency and Concurrency

Every command requires:

```text
Authorization
Idempotency-Key
If-Match
X-Correlation-ID
```

Same key and same payload replay the first result. Same key with a different payload returns an idempotency conflict. Version mismatch returns `412 VERSION_CONFLICT`; last-write-wins is forbidden.

## 8. Error Codes

`TICKET_NOT_FOUND`, `INVALID_STATUS_TRANSITION`, `CLOSE_REASON_INVALID`, `REOPEN_REASON_INVALID`, `RESOLUTION_CYCLE_NOT_FOUND`, `ASSIGNEE_INACTIVE`, `FORBIDDEN`, `QUEUE_ACCESS_DENIED`, `VERSION_CONFLICT`, `PRECONDITION_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`, `VALIDATION_ERROR`.

## 9. File Index

- `acceptance-criteria_CN.md` / `acceptance-criteria_EN.md`
- `domain-rules_CN.md` / `domain-rules_EN.md`
- `api-contract_CN.md` / `api-contract_EN.md`
- `persistence_CN.md` / `persistence_EN.md`
- `event-contract_CN.md` / `event-contract_EN.md`
- `test-plan_CN.md` / `test-plan_EN.md`
- `openapi.yaml`
- `asyncapi.yaml`
- `examples.http`
- `V011__close_and_reopen_ticket.sql`
