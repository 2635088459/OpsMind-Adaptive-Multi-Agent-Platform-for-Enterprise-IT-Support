# SPEC-TW-013 — User Reply and Resume

## 1. Goal

Allow the requester to reply to the current open user input request, and in one transaction save the message, close the request, and move the ticket from `WAITING_FOR_USER` back to `IN_PROGRESS`.

## 2. Scope

Included:

- `POST /api/v1/tickets/{ticketId}/user-input-requests/{requestId}/reply`
- requester reply message
- `WAITING_FOR_USER -> IN_PROGRESS`
- close current input request
- clear `waiting_for_requester_since`
- status history, timeline, audit, outbox
- `ticket.user-reply-received.v1`
- `ticket.user-input-resumed.v1`

Excluded:

- support replying on behalf of requester;
- notification delivery;
- actual Agent runtime execution;
- timeout escalation;
- Approval.

## 3. Core Rules

- Ticket belongs to the current requester;
- ticket status is `WAITING_FOR_USER`;
- request is the current open request for the ticket;
- reply references the current request;
- message save and status resume commit atomically;
- duplicate reply does not resume twice;
- old-request reply may be saved as a normal message but must not resume the ticket.

## 4. Events

```text
ticket.user-reply-received.v1
ticket.user-input-resumed.v1
```

## 5. File Index

- `acceptance-criteria_CN.md` / `acceptance-criteria_EN.md`
- `domain-rules_CN.md` / `domain-rules_EN.md`
- `api-contract_CN.md` / `api-contract_EN.md`
- `persistence_CN.md` / `persistence_EN.md`
- `event-contract_CN.md` / `event-contract_EN.md`
- `test-plan_CN.md` / `test-plan_EN.md`
- `openapi.yaml`
- `asyncapi.yaml`
- `examples.http`
- `V013__user_reply_and_resume.sql`
