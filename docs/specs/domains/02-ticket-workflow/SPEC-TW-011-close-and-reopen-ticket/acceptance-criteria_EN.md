# SPEC-TW-011 — Acceptance Criteria

## AC-01 Close Success

**Given** a `RESOLVED` ticket, an existing current resolution cycle, an actor authorized to close in the queue, a matching `If-Match`, and a new idempotency key  
**When** `POST /api/v1/tickets/{ticketId}/closure` is called with a valid `closeReason`  
**Then** return `200 OK`, move the ticket to `CLOSED`, store `closedBy`, `closedAt`, and `closeReason`, close the current cycle, increment version, and atomically write status history, timeline, audit, idempotency, and `ticket.closed.v1` outbox.

## AC-02 Close State Rules

- `IN_PROGRESS -> CLOSED` returns `409 INVALID_STATUS_TRANSITION`.
- `WAITING_FOR_USER -> CLOSED` returns `409 INVALID_STATUS_TRANSITION`.
- `CLOSED -> CLOSED` returns `409 INVALID_STATUS_TRANSITION`.
- `OPEN/TRIAGED/ASSIGNED -> CLOSED` returns `409 INVALID_STATUS_TRANSITION`.

## AC-03 Close Reason

- `closeReason` is required.
- Trimmed length is 3 to 500.
- Reason code belongs to the controlled enum.
- Errors return stable `CLOSE_REASON_INVALID` or `VALIDATION_ERROR`.

## AC-04 Reopen RESOLVED

**Given** a `RESOLVED` ticket  
**When** an authorized actor calls `POST /api/v1/tickets/{ticketId}/reopen`  
**Then** the ticket enters `IN_PROGRESS`, `reopen_count` increments, a new active resolution cycle is created, current resolution fields are cleared, assignee is retained, and `ticket.reopened.v1` is written.

## AC-05 Reopen CLOSED

**Given** a `CLOSED` ticket  
**When** an authorized actor reopens it within the allowed window  
**Then** the ticket enters `IN_PROGRESS`, close fields are cleared from the current ticket but preserved in the old cycle/history, and a new cycle becomes current.

## AC-06 Reopen Reason

- `reopenReason` is required.
- Trimmed length is 10 to 1000.
- Optional `reopenReasonCode` belongs to the controlled enum.
- Secrets, tokens, passwords, and full logs are not allowed.

## AC-07 Ownership

Reopen retains the previous assignee by default. If the assignee is missing or inactive:

- do not silently assign another user;
- response includes an ownership warning;
- active work must be corrected through assign/reassign.

## AC-08 Idempotency

- Same idempotency key and same payload replay the original response.
- Same key with a different payload returns `409 IDEMPOTENCY_KEY_REUSED`.
- Replay does not create duplicate timeline, history, audit, or outbox records.

## AC-09 Concurrency

Concurrent close/reopen against the same version allows only one success. The loser returns `412 VERSION_CONFLICT` or `409 INVALID_STATUS_TRANSITION` for the now-current state.

## AC-10 Transaction Atomicity

Ticket, resolution cycle, status history, timeline, audit, outbox, and finalized idempotency response commit in one transaction. Any failure rolls back the whole command.

## AC-11 Events

Close publishes only `ticket.closed.v1`. Reopen publishes only `ticket.reopened.v1`. Failed commands publish no success event.
