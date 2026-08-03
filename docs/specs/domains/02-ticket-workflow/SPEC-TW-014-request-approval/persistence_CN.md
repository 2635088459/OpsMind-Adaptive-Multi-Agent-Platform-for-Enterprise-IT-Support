# SPEC-TW-014 — 持久化设计

真实 migration 建议：`V020__request_approval.sql`。

新增表 `ticket.ticket_approval_requests`：

- `approval_request_id UUID`
- `ticket_id UUID`
- `approval_id VARCHAR(128)`
- `workflow_id VARCHAR(128)`
- `action_id VARCHAR(128)`
- `action_type VARCHAR(64)`
- `request_status VARCHAR(24)`
- `risk_level VARCHAR(24)`
- `risk_context JSONB`
- `requested_by_type`
- `requested_by_id`
- `requested_at`
- `expires_at`

唯一约束：每个 Ticket 只能有一个 `OPEN` approval request。
