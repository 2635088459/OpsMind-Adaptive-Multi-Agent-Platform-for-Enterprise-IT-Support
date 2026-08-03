# SPEC-TW-016 — Persistence Design

Real migration: `V022__apply_approval_rejected.sql`.

Add or confirm `rejected_by`, `rejected_at`, `rejection_reason`, and `rejected_event_id`. Ticket resumes `IN_PROGRESS` and clears approval reference.
