# SPEC-TW-013 — Acceptance Criteria

## AC-01 Reply and Resume Success

**Given** the ticket is `WAITING_FOR_USER`, requestId points to the current open request, actor is the ticket requester, `If-Match` matches, and the idempotency key is new  
**When** requester submits a valid reply  
**Then** save the message, mark the request `ANSWERED`, move the ticket to `IN_PROGRESS`, clear waiting metadata, increment version, and atomically write history, timeline, audit, and outbox.

## AC-02 State and Request Rules

- Non-`WAITING_FOR_USER` ticket cannot resume.
- Request not belonging to the ticket returns 404 or 403.
- Non-`OPEN` request cannot resume.
- Old-request reply must not resume the current ticket.

## AC-03 Reply Content

Reply is required, trimmed length 1 to 10000. Attachment references belong to the current ticket/request and have completed security scanning.

## AC-04 Idempotency and Duplicate Replies

Same key/payload replays the first response. Concurrent replies to the same request allow only one resume; later replies may be stored as normal messages but must not transition status again.

## AC-05 Events

A valid current reply publishes `ticket.user-reply-received.v1` and `ticket.user-input-resumed.v1`. Normal messages publish only message events, not resumed events.
