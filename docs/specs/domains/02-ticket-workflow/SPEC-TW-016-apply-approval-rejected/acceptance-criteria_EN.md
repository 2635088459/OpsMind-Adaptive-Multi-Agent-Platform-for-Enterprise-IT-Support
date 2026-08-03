# SPEC-TW-016 — Acceptance Criteria

- Matching rejected event applies successfully.
- Ticket moves from `WAITING_FOR_APPROVAL` to `IN_PROGRESS`.
- Request status becomes `REJECTED`, storing `rejectedBy`, `rejectedAt`, and `rejectionReason`.
- Duplicate is idempotent.
- Wrong producer/schema invalid goes to DLQ.
- Stale event does not advance the ticket.
