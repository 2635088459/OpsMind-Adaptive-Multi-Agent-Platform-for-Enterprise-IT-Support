# SPEC-TW-012 — Request User Input

## 1. Goal

Allow an authorized support actor or Automation Agent to request additional requester information while a ticket is in progress, moving the ticket from `IN_PROGRESS` to `WAITING_FOR_USER`.

A successful command creates one open user input request, records the requester-facing prompt, stores waiting metadata, and writes status history, timeline, audit, outbox, and idempotency response.

## 2. Authorities

- `phase-04-waiting-for-user_CN.md`
- `phase-04-waiting-for-user_EN.md`
- `phase-03-ticket-lifecycle-and-ownership_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/03-state-machine/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/07-data-model/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/09-concurrency-and-idempotency/README_CN.md`

Where the earlier state machine uses `TRIAGING / INVESTIGATING`, Phase 04 maps those states to `IN_PROGRESS`.

## 3. Scope

Included:

- `POST /api/v1/tickets/{ticketId}/user-input-requests`
- `IN_PROGRESS -> WAITING_FOR_USER`
- create `ticket_user_input_requests`
- ensure one open request per ticket
- record prompt, requestedBy, requestedAt, and resumeStatus
- set `waiting_for_requester_since`
- status history, timeline, audit, outbox
- `ticket.user-input-requested.v1`

Excluded:

- requester reply;
- notification delivery;
- SLA breach engine;
- timeout escalation;
- Approval or tool execution.

## 4. Core Rules

- only `IN_PROGRESS -> WAITING_FOR_USER` is allowed;
- ticket must have an assignee;
- actor must have target support queue access;
- prompt is requester-facing and safe to display;
- no second `OPEN` user input request is allowed for the same ticket;
- assignee is retained;
- resolve/close remain state-machine constrained and cannot bypass waiting-for-user.

## 5. Event

```text
ticket.user-input-requested.v1
```

## 6. File Index

- `acceptance-criteria_CN.md` / `acceptance-criteria_EN.md`
- `domain-rules_CN.md` / `domain-rules_EN.md`
- `api-contract_CN.md` / `api-contract_EN.md`
- `persistence_CN.md` / `persistence_EN.md`
- `event-contract_CN.md` / `event-contract_EN.md`
- `test-plan_CN.md` / `test-plan_EN.md`
- `openapi.yaml`
- `asyncapi.yaml`
- `examples.http`
- `V012__request_user_input.sql`
