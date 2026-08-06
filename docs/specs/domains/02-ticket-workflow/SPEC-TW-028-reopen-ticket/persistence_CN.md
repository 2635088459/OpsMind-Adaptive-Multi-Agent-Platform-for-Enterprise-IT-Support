# SPEC-TW-028 持久化设计

## Aggregate 更新

- `tickets.state`：目标效果 `REOPENED`；
- `tickets.workflow_version`：CAS 成功后递增；
- `tickets.updated_at`：command commit time；
- ownership/resolution/escalation 字段按本 SPEC 语义更新。

## Audit Table

参考 migration：`V028__reopen_ticket.sql`。

建议表：`ticket_phase8_reopen_ticket_audit`，字段：

- `id`、`ticket_id`、`workflow_version`；
- `actor_id`、`reason_code`、`reason`；
- `from_state`、`to_state`；
- `idempotency_key`、`correlation_id`、`causation_id`；
- `created_at`。

## Outbox

- outbox topic/event：`ticket.reopened.v1`；
- payload 引用 audit row id；
- event publish 失败由 outbox relay 重试。
