# SPEC-TW-019 — Persistence Design

Real migration: `V025__tool_execution_completed.sql`.

Add or confirm `ticket_tool_execution_results`:

- `tool_execution_id`
- `ticket_id`
- `workflow_id`
- `action_id`
- `authorization_reference`
- `result_status`
- `tool_result_id`
- `completed_at`
- `result_summary`
- `event_id`

Unique constraint: `tool_execution_id` is unique to prevent duplicate business effects.
