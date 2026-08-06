# SPEC-TW-022 — Acceptance Criteria

- `VERIFYING` ticket can start a verification attempt.
- Non-`VERIFYING` status is rejected.
- Tool result mismatch with current workflow/cycle/action is rejected.
- Duplicate idempotency replay does not create a second attempt.
- Existing active attempt for the same tool result returns conflict.
- Success writes attempt, timeline, audit, and outbox.
