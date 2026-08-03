# SPEC-TW-017 — Persistence Design

Real migration: `V023__apply_approval_expired.sql`.

Add or confirm `expired_at`, `expired_event_id`, and `expiration_reason`. Ticket resumes `IN_PROGRESS`; approval request becomes `EXPIRED`.
