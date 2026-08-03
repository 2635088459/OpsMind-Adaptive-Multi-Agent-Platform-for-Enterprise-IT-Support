# SPEC-TW-016 — 持久化设计

真实 migration：`V022__apply_approval_rejected.sql`。

新增或确认 `rejected_by`、`rejected_at`、`rejection_reason`、`rejected_event_id`。Ticket 恢复 `IN_PROGRESS` 并清理 approval reference。
