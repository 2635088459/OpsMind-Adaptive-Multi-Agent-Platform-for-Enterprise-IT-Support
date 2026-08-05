# SPEC-TW-021 — Acceptance Criteria

- Matching unknown event is recorded successfully.
- Ticket enters `ESCALATED` or reconciliation-required state.
- Store uncertainty reason, evidence references, and observedAt.
- Duplicate is idempotently ACKed.
- Late completed/failed event is classified as conflict/stale and does not silently overwrite.
- Wrong producer/schema invalid goes to DLQ.
- Original ToolExecutionId is not automatically retried.
