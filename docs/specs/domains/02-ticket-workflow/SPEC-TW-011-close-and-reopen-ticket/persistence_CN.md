# SPEC-TW-011 — 持久化设计

## 1. Migration 策略

Spec 目录提供参考迁移：

```text
V011__close_and_reopen_ticket.sql
```

真实 service migration 应按当前序列命名：

```text
services/ticket-workflow-service/src/main/resources/db/migration/V017__close_and_reopen_ticket.sql
```

原因：`SPEC-TW-010` 的真实 migration 预期使用 `V016__resolve_ticket.sql`。

## 2. Ticket 表字段

`ticket.tickets` 需要新增或确认：

- `closed_by VARCHAR(128)`；
- `closed_at TIMESTAMPTZ`；
- `close_reason_code VARCHAR(64)`；
- `reopen_count INTEGER NOT NULL DEFAULT 0`；
- `last_reopened_at TIMESTAMPTZ`；
- `last_reopened_by VARCHAR(128)`；
- `last_reopen_reason_code VARCHAR(64)`；
- 已存在或由 SPEC-010 增加的 resolution fields。

## 3. Close Update

推荐条件更新：

```sql
UPDATE ticket.tickets
SET status = 'CLOSED',
    closed_at = :closed_at,
    closed_by = :closed_by,
    close_reason_code = :close_reason_code,
    auto_close_due_at = NULL,
    active_workflow_id = NULL,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status = 'RESOLVED'
```

数据库 CHECK 应确保 `CLOSED` 至少有 `resolved_at`、`closed_at`、`closed_by` 和 `close_reason_code`。

## 4. Reopen Update

Reopen 必须在同一事务中：

1. 锁定当前 Ticket；
2. 验证状态为 `RESOLVED` 或 `CLOSED`；
3. 归档旧 resolution cycle；
4. 插入新 `ticket.ticket_resolution_cycles` row，`cycle_status = ACTIVE`；
5. 更新 Ticket 指向新 cycle；
6. 清空当前 resolution/close 字段；
7. `reopen_count = reopen_count + 1`；
8. 写 history/timeline/audit/outbox/idempotency。

推荐 Ticket 更新：

```sql
UPDATE ticket.tickets
SET status = 'IN_PROGRESS',
    current_resolution_cycle_id = :new_resolution_cycle_id,
    resolved_at = NULL,
    resolved_by = NULL,
    resolution_code = NULL,
    resolution_summary = NULL,
    auto_close_due_at = NULL,
    closed_at = NULL,
    closed_by = NULL,
    close_reason_code = NULL,
    last_reopened_at = :reopened_at,
    last_reopened_by = :reopened_by,
    last_reopen_reason_code = :reopen_reason_code,
    reopen_count = reopen_count + 1,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status IN ('RESOLVED', 'CLOSED')
```

## 5. Resolution Cycle

Close：

- 当前 cycle 从 `RESOLVED` 更新为 `CLOSED`；
- 保存 `closed_at`、`closed_by_type`、`closed_by_id`、`close_reason_code`。

Reopen：

- 旧 cycle 保留 resolved/closed snapshot；
- 旧 cycle 记录 `reopened_at`、`reopened_by_type`、`reopened_by_id`、`reopen_reason_code`；
- 新 cycle 使用 `cycle_number = previous + 1`，状态 `ACTIVE`。

## 6. Status History

Close：

```text
transition_id = SM-011
reason_code = TICKET_CLOSED
from_status = RESOLVED
to_status = CLOSED
```

Reopen：

```text
transition_id = SM-012 or SM-013
reason_code = TICKET_REOPENED
from_status = RESOLVED or CLOSED
to_status = IN_PROGRESS
```

## 7. 约束与索引

建议增加：

```sql
CREATE INDEX ix_tickets_closed_at
    ON ticket.tickets (closed_at DESC)
    WHERE status = 'CLOSED';

CREATE INDEX ix_tickets_reopen_count
    ON ticket.tickets (reopen_count)
    WHERE reopen_count > 0;
```

CHECK 约束应防止负数 `reopen_count`，并限制 close/reopen reason code。
