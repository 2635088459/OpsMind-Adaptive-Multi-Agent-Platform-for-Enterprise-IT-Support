# SPEC-TW-010 — Acceptance Criteria

## AC-01 — Successful Resolution

**Given** an `IN_PROGRESS` ticket with an assignee, an active resolution cycle, an actor authorized for the queue, a matching `If-Match`, and a new idempotency key  
**When** a valid `resolutionCode` and `resolutionSummary` are submitted  
**Then** return `200 OK`, change status to `RESOLVED`, store resolution data, complete the resolution cycle, increment version, return the new `ETag`, and atomically write history, timeline, audit, idempotency, and outbox records.

## AC-02 — Resolution Summary

Trimmed `resolutionSummary` must be 10 to 5000 characters. Blank, too-short, or too-long values return `400 VALIDATION_ERROR`.

## AC-03 — Resolution Code

An unsupported `resolutionCode` returns `400 VALIDATION_ERROR` or `422 RESOLUTION_CODE_INVALID` and writes no business records.

## AC-04 — Invalid States

- `ASSIGNED -> RESOLVED` returns `409 INVALID_STATUS_TRANSITION`.
- `WAITING_FOR_USER -> RESOLVED` returns `409 INVALID_STATUS_TRANSITION`.
- `WAITING_FOR_APPROVAL -> RESOLVED` returns `409 INVALID_STATUS_TRANSITION`.
- `RESOLVED -> RESOLVED` returns `409 INVALID_STATUS_TRANSITION`.
- `CLOSED -> RESOLVED` returns `409 INVALID_STATUS_TRANSITION`.

## AC-05 — Assignee Required

A ticket without `current_support_user_id` cannot be resolved and returns `409 TICKET_NOT_ASSIGNED`.

## AC-06 — Resolution Cycle

The current `current_resolution_cycle_id` must exist and be incomplete. Missing cycles return `409 RESOLUTION_CYCLE_NOT_FOUND`; completed cycles return `409 RESOLUTION_CYCLE_ALREADY_COMPLETED`.

## AC-07 — Metadata Cleanup

Successful resolution clears `waiting_for_requester_since` and `approval_reference`, and sets `resolved_by`, `resolved_at`, `resolution_code`, and `resolution_summary`.

## AC-08 — Authorization

Requesters and actors without `ticket:resolve` receive `403 FORBIDDEN`. Actors with scope but without queue access receive `403 QUEUE_ACCESS_DENIED`.

## AC-09 — Optimistic Locking

Missing or blank `If-Match` returns `428 PRECONDITION_REQUIRED`. Stale versions return `412 VERSION_CONFLICT` with current-version details. At most one concurrent resolve against the same version may commit.

## AC-10 — Idempotency

The same key and canonical request return the first successful response without duplicate writes. The same key with a different request fingerprint returns `409 IDEMPOTENCY_KEY_REUSED`.

## AC-11 — Atomicity

Failure of ticket update, resolution-cycle update, status history, timeline, audit, outbox, or idempotency persistence rolls back the whole command.

## AC-12 — Contract and Privacy

Success responses return ticket ID, previous status, status, resolution code, summary, resolvedBy, resolvedAt, and version. Events and logs must not contain tokens, raw claims, private messages, or secrets.
