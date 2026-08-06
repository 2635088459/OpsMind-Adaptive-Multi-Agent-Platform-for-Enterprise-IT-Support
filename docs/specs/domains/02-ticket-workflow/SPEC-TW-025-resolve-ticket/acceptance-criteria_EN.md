# SPEC-TW-025 — Acceptance Criteria

- Trusted current verification evidence can resolve.
- Missing evidence returns `409 VERIFICATION_REQUIRED`.
- Old workflow/cycle/attempt evidence is rejected.
- Resolution code/summary validation matches Phase 03.
- Success completes the resolution cycle and enters `RESOLVED`.
- Publish `ticket.resolved-with-verification.v1`.
- Duplicate idempotency replay creates no duplicate effects.
