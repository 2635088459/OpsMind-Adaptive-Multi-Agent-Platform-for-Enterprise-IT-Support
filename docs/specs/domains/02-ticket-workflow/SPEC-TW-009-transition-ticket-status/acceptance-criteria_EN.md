# SPEC-TW-009 — Acceptance Criteria

## AC-01 — Start Work

**Given** an `ASSIGNED` ticket with an assignee, an actor authorized for the queue, a matching `If-Match`, and a new idempotency key  
**When** target status `IN_PROGRESS` is submitted  
**Then** return `200 OK`, change status to `IN_PROGRESS`, increment the version, return the new `ETag`, and atomically write status history, timeline, audit, idempotency, and outbox records.

## AC-02 — Wait for Requester

**Given** an `IN_PROGRESS` ticket  
**When** target status `WAITING_FOR_USER` and a reason are submitted  
**Then** status becomes `WAITING_FOR_USER`, `waitingForRequesterSince` is stored, `approvalReference` is cleared, and `ticket.status-changed.v1` is published.

## AC-03 — Wait for Approval

**Given** an `IN_PROGRESS` ticket  
**When** target status `WAITING_FOR_APPROVAL`, a reason, and `approvalReference` are submitted  
**Then** status becomes `WAITING_FOR_APPROVAL`, `approvalReference` is stored, `waitingForRequesterSince` is cleared, and `ticket.status-changed.v1` is published.

## AC-04 — Resume Work

**Given** a ticket in `WAITING_FOR_USER` or `WAITING_FOR_APPROVAL`  
**When** target status `IN_PROGRESS` is submitted  
**Then** status returns to `IN_PROGRESS`, all waiting metadata is cleared, the version increments, and the resume reason is recorded.

## AC-05 — Invalid Transitions

- `NEW -> IN_PROGRESS` returns `409 INVALID_STATUS_TRANSITION`.
- `TRIAGED -> IN_PROGRESS` returns `409 INVALID_STATUS_TRANSITION`.
- `ASSIGNED -> WAITING_FOR_USER` returns `409 INVALID_STATUS_TRANSITION`.
- `WAITING_FOR_USER -> WAITING_FOR_APPROVAL` returns `409 INVALID_STATUS_TRANSITION`.
- Using this endpoint to enter `RESOLVED` or `CLOSED` returns `409 INVALID_STATUS_TRANSITION`.
- Invalid transitions write no business-success records.

## AC-06 — Assignee Required

Every successful transition into `IN_PROGRESS`, `WAITING_FOR_USER`, or `WAITING_FOR_APPROVAL` requires `current_support_user_id`. Missing ownership returns `409 TICKET_NOT_ASSIGNED`.

## AC-07 — Authorization

Requesters and actors without `ticket:transition` receive `403 FORBIDDEN`. Actors with scope but without queue access receive `403 QUEUE_ACCESS_DENIED`. Actor identity is derived only from trusted authentication context.

## AC-08 — Optimistic Locking

Missing or blank `If-Match` returns `428 PRECONDITION_REQUIRED`. Stale versions return `412 VERSION_CONFLICT` with current-version details. At most one of two concurrent transitions against the same version may commit.

## AC-09 — Idempotency

The same key and canonical request return the first successful response without duplicate writes. Reusing the key with a different request fingerprint returns `409 IDEMPOTENCY_KEY_REUSED`.

## AC-10 — Atomicity

Failure of ticket update, status history, timeline, audit, outbox, or idempotency persistence rolls back the whole command.

## AC-11 — Contract and Privacy

Success responses return ticket ID, previous status, current status, waiting metadata, version, and `ETag`. Errors use shared Problem Details. Events and logs must not contain tokens, raw claims, full identity profiles, or private message content.

## AC-12 — Observability

Metrics, structured logs, and tracing distinguish start-work, wait-for-user, wait-for-approval, and resume-work outcomes: success, denial, conflict, and replay.
