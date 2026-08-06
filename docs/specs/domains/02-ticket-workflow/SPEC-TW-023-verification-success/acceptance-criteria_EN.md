# SPEC-TW-023 — Acceptance Criteria

- Matching current active attempt success applies.
- Attempt is marked `SUCCEEDED`.
- Trusted verification evidence is stored.
- Duplicate is idempotently ACKed.
- Old workflow/cycle/attempt is recorded as stale.
- Conflicting failure result enters reconciliation.
- Ticket is not closed directly.
