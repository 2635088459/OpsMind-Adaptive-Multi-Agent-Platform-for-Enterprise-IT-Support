# SPEC-TW-015 — 持久化设计

真实 migration 建议：`V021__apply_approval_granted.sql`。

需要保存：

- approval request `request_status = GRANTED`；
- `approved_by`、`approved_at`；
- `authorization_reference`；
- consumed event id；
- Ticket `status = IN_PROGRESS`；
- 清理 `approval_reference` 或标记为 consumed。
