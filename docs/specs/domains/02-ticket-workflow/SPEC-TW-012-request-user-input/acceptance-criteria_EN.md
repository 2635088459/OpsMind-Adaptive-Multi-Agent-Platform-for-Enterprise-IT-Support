# SPEC-TW-012 — Acceptance Criteria

## AC-01 Request User Input Success

**Given** an `IN_PROGRESS` ticket with an assignee, no open user input request, an actor authorized for the queue, a matching `If-Match`, and a new idempotency key  
**When** the request user input endpoint is called with a valid prompt  
**Then** return `201 Created`, move the ticket to `WAITING_FOR_USER`, create an open request, set `waitingForRequesterSince`, increment version, and atomically write history, timeline, audit, outbox, and idempotency response.

## AC-02 State Rules

- Non-`IN_PROGRESS` status returns `409 INVALID_STATUS_TRANSITION`.
- `WAITING_FOR_USER` repeated request returns `409 USER_INPUT_REQUEST_ALREADY_OPEN`.
- `RESOLVED`, `CLOSED`, `OPEN`, `TRIAGED`, and `ASSIGNED` cannot enter waiting-for-user.

## AC-03 Open Request Uniqueness

At most one `OPEN` request exists per ticket. Concurrent create allows only one success; the loser returns conflict or version mismatch.

## AC-04 Prompt

Prompt is required, trimmed length 10 to 2000, and must not contain secrets, tokens, passwords, private keys, internal tool logs, or unsafe instructions to the requester.

## AC-05 Authorization

Requester cannot create a support user-input request. Support actor must have queue-level authorization. Automation Agent must use a service identity.

## AC-06 Idempotency

Same key/payload replays the first response without duplicate request, history, timeline, or outbox. Same key with different payload returns `409 IDEMPOTENCY_KEY_REUSED`.

## AC-07 Event

Success publishes only `ticket.user-input-requested.v1`. Failed commands publish no success event.
