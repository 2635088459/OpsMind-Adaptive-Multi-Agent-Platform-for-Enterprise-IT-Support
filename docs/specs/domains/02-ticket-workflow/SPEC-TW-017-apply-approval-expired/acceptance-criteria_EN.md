# SPEC-TW-017 — Acceptance Criteria

- Matching expired event applies successfully.
- `expiredAt >= expiresAt` or local clock confirms expiration.
- Ticket returns to `IN_PROGRESS`.
- Duplicate is idempotent.
- Granted vs expired race is decided by committed terminal state.
- Stale/wrong-producer/schema-invalid classification is correct.
