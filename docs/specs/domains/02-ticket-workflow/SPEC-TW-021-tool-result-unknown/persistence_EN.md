# SPEC-TW-021 — Persistence Design

Real migration: `V027__tool_result_unknown.sql`.

Reuse `ticket_tool_execution_results`, adding or confirming:

- `unknown_reason`
- `observed_at`
- `evidence_references JSONB`
- `reconciliation_required BOOLEAN`
- `conflict_event_id`

`tool_execution_id` remains the unique business key.
