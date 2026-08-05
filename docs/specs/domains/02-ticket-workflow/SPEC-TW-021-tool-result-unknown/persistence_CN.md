# SPEC-TW-021 — 持久化设计

真实 migration 建议：`V027__tool_result_unknown.sql`。

复用 `ticket_tool_execution_results`，新增或确认：

- `unknown_reason`
- `observed_at`
- `evidence_references JSONB`
- `reconciliation_required BOOLEAN`
- `conflict_event_id`

`tool_execution_id` 仍为唯一业务 key。
