# SPEC-TW-020 — Acceptance Criteria

- Known-safe failed event applies and ticket returns to `IN_PROGRESS`.
- Pipeline/internal failure may enter `FAILED`.
- Unsafe unknown side effect is rejected and routed to SPEC-TW-021 handling.
- Store `toolExecutionId`, failure code, failure class, and failedAt.
- Duplicate is idempotently ACKed.
- Wrong producer/schema invalid goes to DLQ.
- Stale event does not advance the ticket.
