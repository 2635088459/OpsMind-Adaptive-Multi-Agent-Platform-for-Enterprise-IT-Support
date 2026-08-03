# SPEC-TW-017 — 持久化设计

真实 migration：`V023__apply_approval_expired.sql`。

新增或确认 `expired_at`、`expired_event_id`、`expiration_reason`。Ticket 恢复 `IN_PROGRESS`，approval request 进入 `EXPIRED`。
