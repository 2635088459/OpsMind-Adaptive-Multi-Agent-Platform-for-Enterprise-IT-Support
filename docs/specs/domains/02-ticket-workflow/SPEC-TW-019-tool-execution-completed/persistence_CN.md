# SPEC-TW-019 — 持久化设计

真实 migration 建议：`V025__tool_execution_completed.sql`。

新增或确认 `ticket_tool_execution_results`：

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

唯一约束：`tool_execution_id` 唯一，防止重复业务效果。
