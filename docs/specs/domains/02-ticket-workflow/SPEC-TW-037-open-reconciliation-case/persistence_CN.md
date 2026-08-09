# SPEC-TW-037 持久化设计

## 参考 Migration

`V037__open_reconciliation_case.sql`

## 建议表

`ticket_phase10_open_reconciliation_case`：

- `id`、`ticket_id`、`source_reference`；
- `decision`、`reason_code`、`reason`；
- `actor_id`、`correlation_id`、`causation_id`；
- `attempt_number`、`created_at`、`completed_at`。

## 事务边界

recovery mutation、audit record、idempotency completion 和 outbox/correction record 必须在同一明确事务内提交，或通过显式 saga/retry 记录状态。
