# Phase 04 — Waiting for User Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 04
>
> Specs: `SPEC-TW-012` through `SPEC-TW-013`
>
> Prerequisites: Phase 01, Phase 02, and Phase 03 implemented and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 04 implements the branch where support needs additional requester information, and the ticket resumes once the requester replies.

The current authoritative Phase 03 state model is:

```text
IN_PROGRESS -> WAITING_FOR_USER -> IN_PROGRESS
```

The earlier roadmap's `TRIAGING / INVESTIGATING` states belong to the pre-freeze state machine. In the current implementation, they map to `IN_PROGRESS`. If a finer workflow runtime state is reintroduced later, it must not change the Ticket persistence boundary.

## 2. Business Value

Real IT support often needs device details, screenshots, timestamps, reproduction steps, or confirmation from the requester. Without Phase 04, `WAITING_FOR_USER` is only a status, not an auditable request-reply loop.

Phase 04 provides:

- at most one open user input request per ticket;
- explicit questions from support or Automation Agent;
- `WAITING_FOR_USER` ticket status with waiting metadata;
- requester replies tied to the current open request;
- atomic message persistence and status resume;
- stale requests, duplicate replies, and out-of-order events do not resume the wrong workflow;
- timeline, audit, outbox, and idempotency consistent with Phase 03.

## 3. Scope

### 3.1 Included

- create user input request;
- `IN_PROGRESS -> WAITING_FOR_USER`;
- store requester-facing prompt, requestedBy, requestedAt, and resumeStatus;
- pause or mark SLA waiting time;
- requester replies to current open request;
- atomically save message and perform `WAITING_FOR_USER -> IN_PROGRESS`;
- close user input request;
- clear waiting metadata;
- status history, timeline, audit, outbox;
- idempotency, optimistic locking, authorization, and error model;
- contract, integration, and E2E tests.

### 3.2 Excluded

- Approval;
- tool execution;
- notification delivery;
- automatic timeout escalation;
- SLA breach engine;
- multi-step forms;
- requester directly changing status;
- actual Agent runtime resume execution.

## 4. Phase 04 Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-012` | Request User Input | Create an input request and enter waiting-for-user |
| 2 | `SPEC-TW-013` | User Reply and Resume | Reply to the current request and resume work |

## 5. State Transitions

| Current | Target | Trigger |
|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_USER` | Request User Input |
| `WAITING_FOR_USER` | `IN_PROGRESS` | User Reply and Resume |

Every unlisted transition is illegal. `WAITING_FOR_USER -> RESOLVED`, `WAITING_FOR_USER -> CLOSED`, and `CLOSED -> WAITING_FOR_USER` must be rejected.

## 6. API Command Boundaries

```text
POST /api/v1/tickets/{ticketId}/user-input-requests
POST /api/v1/tickets/{ticketId}/user-input-requests/{requestId}/reply
```

Every write request should support:

```text
Authorization: Bearer <token>
Idempotency-Key: <uuid>
If-Match: "<ticket-version>"
X-Correlation-ID: <uuid>
```

## 7. Domain Events

```text
ticket.user-input-requested.v1
ticket.user-reply-received.v1
ticket.user-input-resumed.v1
```

`ticket.user-reply-received.v1` means the message was saved. `ticket.user-input-resumed.v1` means the valid current request was closed and the ticket resumed.

## 8. Data Model

Add or confirm:

```text
ticket_user_input_requests
ticket_messages
ticket_status_history
ticket_timeline
ticket_audit_log
outbox_events
idempotency_records
```

`ticket_user_input_requests` includes at least:

- `request_id`
- `ticket_id`
- `request_status`
- `prompt`
- `requested_by_type`
- `requested_by_id`
- `requested_at`
- `resume_status`
- `answered_message_id`
- `answered_at`
- `expires_at`
- `correlation_id`

## 9. Transactions and Consistency

Request User Input success happens in one transaction:

1. validate ticket, state, version, and authorization;
2. confirm no open input request exists;
3. create input request;
4. update ticket to `WAITING_FOR_USER`;
5. write status history, timeline, audit, and outbox;
6. finalize idempotency response.

User Reply and Resume success happens in one transaction:

1. validate ticket, request, requester, and version;
2. save message;
3. close input request;
4. update ticket to `IN_PROGRESS`;
5. clear waiting metadata;
6. write status history, timeline, audit, and outbox;
7. finalize idempotency response.

## 10. Exit Criteria

- `SPEC-TW-012` and `SPEC-TW-013` docs and implementation are complete;
- a ticket never has two open user input requests;
- requester reply must reference the current open request to resume;
- message and status resume commit atomically;
- duplicate reply does not resume twice;
- old request / stale workflow event is rejected or downgraded to a normal message;
- OpenAPI, AsyncAPI, migration, and tests pass.
