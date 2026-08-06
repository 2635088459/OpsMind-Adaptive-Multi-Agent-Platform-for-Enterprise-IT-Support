# SPEC-TW-024 — Acceptance Criteria

- Retryable failure returns to `IN_PROGRESS`.
- Third failure enters `ESCALATED`.
- Unsafe result enters `ESCALATED`.
- Pipeline failure may enter `FAILED`.
- Duplicate is idempotent.
- Old workflow/cycle/attempt is stale.
- Conflicting success enters reconciliation.
