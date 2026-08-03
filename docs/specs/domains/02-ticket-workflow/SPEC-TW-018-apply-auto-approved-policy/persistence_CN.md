# SPEC-TW-018 — 持久化设计

真实 migration：`V024__apply_auto_approved_policy.sql`。

新增或确认：

- `policy_decision_id`
- `policy_id`
- `policy_version`
- `auto_approved_at`
- `auto_approval_event_id`
- `authorization_reference`

可复用 `ticket_approval_requests`，状态为 `AUTO_APPROVED`。
