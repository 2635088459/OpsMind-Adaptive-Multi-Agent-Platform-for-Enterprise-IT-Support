# SPEC-TW-020 — Persistence Design

Real migration: `V026__tool_execution_failed.sql`.

Reuse `ticket_tool_execution_results`, adding or confirming:

- `failure_code`
- `failure_class`
- `failed_at`
- `safe_to_retry`

`tool_execution_id` remains the unique business key.
