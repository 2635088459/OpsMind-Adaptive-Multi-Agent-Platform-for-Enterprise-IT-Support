# SPEC-TW-014 — Acceptance Criteria

- An `IN_PROGRESS` ticket can create an approval request and enter `WAITING_FOR_APPROVAL`.
- Non-`IN_PROGRESS` status returns `409 INVALID_STATUS_TRANSITION`.
- Existing open approval request returns `409 APPROVAL_REQUEST_ALREADY_OPEN`.
- Missing ticket/workflow/action/risk context returns `400 VALIDATION_ERROR`.
- Success writes ticket, approval request, history, timeline, audit, outbox, and idempotency.
- Same idempotency key/payload replays the first response.
- Same key with different payload returns `409 IDEMPOTENCY_KEY_REUSED`.
