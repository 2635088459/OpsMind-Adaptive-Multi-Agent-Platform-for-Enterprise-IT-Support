# SPEC-TW-009 — 持久化设计

## 1. 迁移策略

Spec 目录提供参考迁移：

```text
V009__transition_ticket_status.sql
```

真实 service 迁移应按当前 Flyway 序列命名：

```text
services/ticket-workflow-service/src/main/resources/db/migration/V015__transition_ticket_status.sql
```

原因：真实代码库已使用 `V013__triage_ticket.sql` 和 `V014__assign_ticket.sql`。

## 2. Ticket 表变化

`ticket.tickets` 需要：

- 在 `ck_tickets_status` 中加入 `IN_PROGRESS`；
- 增加 `waiting_for_requester_since TIMESTAMPTZ`；
- 增加 `approval_reference VARCHAR(128)`；
- 保留并继续使用 `current_support_user_id` 作为负责人字段；
- 保留并继续使用 `support_queue_id/current_team_id` 作为队列授权字段。

## 3. 数据约束

数据库应防御以下不变量：

- `IN_PROGRESS`、`WAITING_FOR_USER`、`WAITING_FOR_APPROVAL` 必须有 `current_support_user_id`；
- `WAITING_FOR_USER` 必须有 `waiting_for_requester_since` 且没有 `approval_reference`；
- `WAITING_FOR_APPROVAL` 必须有 `approval_reference` 且没有 `waiting_for_requester_since`；
- `IN_PROGRESS` 不保留 active waiting metadata。

Application Service 仍必须主动清理 metadata，数据库约束是最后防线。

## 4. Status History

复用已存在的 `ticket.ticket_status_history`：

```text
transition_id:
  SM-005 WORK_STARTED
  SM-006 WAITING_FOR_USER
  SM-007 WAITING_FOR_APPROVAL
  SM-008 WORK_RESUMED
  SM-009 WORK_RESUMED
```

每次成功转换必须写一条 status history。`aggregate_version` 必须等于 Ticket 更新后的版本。

## 5. Timeline、Audit、Outbox

每次成功命令必须在同一事务写入：

- requester-safe timeline item；
- internal audit record；
- outbox row，event type 为 `ticket.status-changed.v1`；
- finalized idempotency replay response。

失败命令不得写入成功 timeline、status history 或 outbox event。

## 6. Repository Update

推荐更新条件：

```sql
UPDATE ticket.tickets
SET status = :new_status,
    waiting_for_requester_since = :waiting_for_requester_since,
    approval_reference = :approval_reference,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status = :expected_status
  AND current_support_user_id IS NOT NULL
```

更新行数为零时，Application Service 根据 guard 中的当前版本、状态和负责人判断错误类型。

## 7. 索引

建议增加：

```sql
CREATE INDEX ix_tickets_status_updated
    ON ticket.tickets (status, updated_at DESC);

CREATE INDEX ix_tickets_waiting_user
    ON ticket.tickets (waiting_for_requester_since)
    WHERE status = 'WAITING_FOR_USER';

CREATE INDEX ix_tickets_waiting_approval
    ON ticket.tickets (approval_reference)
    WHERE status = 'WAITING_FOR_APPROVAL';
```

这些索引支持后续队列、提醒、审批和运营查询，但不实现 SLA 或审批流程。
