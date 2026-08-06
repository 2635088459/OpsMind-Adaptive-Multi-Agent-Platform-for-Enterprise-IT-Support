# SPEC-TW-022 — 持久化设计

真实 migration：`V028__start_verification.sql`。

新增 `ticket_verification_attempts`：

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

唯一约束：同一 tool result 只能有一个 active attempt。
