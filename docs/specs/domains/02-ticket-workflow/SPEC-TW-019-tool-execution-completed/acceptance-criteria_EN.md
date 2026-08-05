# SPEC-TW-019 — Acceptance Criteria

- Matching completed event applies successfully.
- Ticket moves from `EXECUTING` to `VERIFYING`.
- Store `toolExecutionId`, `toolResultId`, `completedAt`, and result summary.
- Write status history, timeline, audit, and outbox.
- Duplicate event is idempotently ACKed.
- Wrong producer/schema invalid goes to DLQ.
- Stale workflow/action/authorization does not advance the ticket.
- Tool success does not directly resolve.
