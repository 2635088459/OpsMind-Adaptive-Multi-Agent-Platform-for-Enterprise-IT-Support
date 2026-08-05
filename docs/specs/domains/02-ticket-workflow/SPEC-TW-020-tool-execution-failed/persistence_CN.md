# SPEC-TW-020 — 持久化设计

真实 migration 建议：`V026__tool_execution_failed.sql`。

复用 `ticket_tool_execution_results`，新增或确认：

- `failure_code`
- `failure_class`
- `failed_at`
- `safe_to_retry`

`tool_execution_id` 仍为唯一业务 key。
