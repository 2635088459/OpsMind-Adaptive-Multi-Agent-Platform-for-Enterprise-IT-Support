# SPEC-TW-008 — Acceptance Criteria

## AC-01 — Initial Assignment

**Given** a `TRIAGED` ticket without an assignee, an eligible active assignee, an authorized actor, a matching `If-Match`, and a new idempotency key  
**When** the assign command is submitted  
**Then** return `200 OK`, set the assignee, change `TRIAGED → ASSIGNED`, increment the version, return the new `ETag`, and atomically write all required records.

## AC-02 — Reassignment

**Given** an assigned ticket in an allowed state and a different eligible assignee  
**When** reassign is submitted  
**Then** replace the assignee, preserve the ticket status, record both assignees, increment the version, and publish `ticket.reassigned.v1`.

## AC-03 — Unassignment

**Given** a ticket in `ASSIGNED`  
**When** an authorized actor submits unassign  
**Then** clear ownership, change `ASSIGNED → TRIAGED`, increment the version, and publish `ticket.unassigned.v1`.

## AC-04 — Invalid States

- Assign outside `TRIAGED` returns `409 INVALID_TICKET_STATE`.
- Reassign outside the allowed states returns `409 INVALID_TICKET_STATE`.
- Unassign from `IN_PROGRESS` or waiting returns `409 INVALID_TICKET_STATE`.
- No state-changing records are written.

## AC-05 — Assignee Eligibility

The service rejects nonexistent, inactive, cross-tenant, non-support, or non-queue-member assignees with the appropriate stable error and no writes.

## AC-06 — Authorization

Requesters and actors without command or queue scope receive `403`. Actor identity and tenant are derived only from trusted authentication context.

## AC-07 — Optimistic Locking

A stale or malformed `If-Match` is rejected. Only one of two concurrent commands against the same version may commit.

## AC-08 — Idempotency

Replaying the same key and canonical command returns the stored response. Reusing the key with a different command fingerprint returns `409 IDEMPOTENCY_KEY_REUSED`.

## AC-09 — Atomicity

Failure of ticket, assignment history, status history, timeline, audit, idempotency, or outbox persistence rolls back every write.

## AC-10 — Traceability and Privacy

Every success contains actor, correlation, causation, ticket, old/new assignee, timestamp, and reason metadata where appropriate. Requester-visible timeline content contains no internal authorization details or secrets.

## AC-11 — Response Contract

Successful responses return ticket ID, status, assignee summary, assignment timestamp, version, and `ETag`. Errors use the shared Problem Details shape.

## AC-12 — Observability

Structured logs and metrics distinguish assign/reassign/unassign results without logging bearer tokens, idempotency keys, or sensitive user attributes.
