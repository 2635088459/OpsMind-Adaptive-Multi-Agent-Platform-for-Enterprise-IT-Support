# SPEC-TW-022 — Persistence Design

Real migration: `V028__start_verification.sql`.

Add `ticket_verification_attempts`:

- `verification_id`
- `ticket_id`
- `resolution_cycle_id`
- `workflow_id`
- `tool_result_id`
- `attempt_number`
- `attempt_status`
- `verification_type`
- `started_at`
- `event_id`

Unique constraint: one active attempt per tool result.
