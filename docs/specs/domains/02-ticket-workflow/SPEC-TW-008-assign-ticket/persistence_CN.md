# SPEC-TW-008 — 持久化设计

## 1. Ticket 字段

Ticket Aggregate 需要：

| 字段 | 类型 | 含义 |
|---|---|---|
| `assignee_id` | UUID nullable | 当前负责人 |
| `assigned_at` | TIMESTAMPTZ nullable | 当前负责人开始负责的时间 |
| `assigned_by` | UUID nullable | 设置当前负责人的 Actor |
| `version` | BIGINT | 乐观锁版本 |

Identity 可能由其他服务管理，因此参考迁移不创建跨服务 Foreign Key。

## 2. 负责人历史

`ticket_assignment_history` 只能追加，包含：

- Tenant 与 Ticket ID；
- `ASSIGNED`、`REASSIGNED` 或 `UNASSIGNED`；
- 之前和之后的负责人；
- 之前和之后的状态；
- Actor、Reason 与时间；
- Correlation 与 Causation ID；
- 操作后的 Ticket Version。

## 3. 已有共享表

命令还会写入 Phase 01/02/007 已建立的表：

- Assign/Unassign 状态变化写入 `ticket_status_history`；
- `ticket_timeline_entries`；
- `audit_log`；
- `idempotency_records`；
- `outbox_events`。

Reassign 不改变状态，因此不新增状态历史。

## 4. 原子写入算法

```text
BEGIN
  锁定或声明幂等键
  按 Tenant 加载 Ticket
  校验 Actor、Assignee、状态与 Expected Version
  UPDATE tickets ... WHERE version = expectedVersion
  INSERT ticket_assignment_history
  INSERT ticket_status_history       -- 仅 Assign/Unassign
  INSERT ticket_timeline_entries
  INSERT audit_log
  INSERT outbox_events
  保存最终幂等响应
COMMIT
```

任何错误都必须执行 `ROLLBACK`。

## 5. 乐观锁更新

```sql
UPDATE tickets
SET assignee_id = :assignee_id,
    assigned_at = :assigned_at,
    assigned_by = :assigned_by,
    status = :status,
    version = version + 1,
    updated_at = :occurred_at
WHERE id = :ticket_id
  AND tenant_id = :tenant_id
  AND version = :expected_version;
```

必须恰好更新一行。

## 6. 查询与索引要求

- 当前 Queue 与负责人负载：`(tenant_id, support_queue_id, assignee_id, status)`；
- 负责人历史：`(tenant_id, ticket_id, occurred_at, id)`；
- Actor 调查：`(tenant_id, actor_id, occurred_at)`。

## 7. Timeline 与 Audit

Requester-safe Timeline 可根据隐私政策包含 Display Name 或稳定 User ID，但不得包含 Role Claims 或 Queue Membership 证明。Audit 保存内部决策元数据、新旧值、Actor、Policy Result、Correlation 与 Source Channel。

## 8. 保留与完整性

- Application API 不得修改负责人历史和 Audit；
- 时间统一使用 UTC；
- 所有新行必须包含 Tenant ID；
- History 的 `resulting_version` 必须等于提交后的 Ticket Version；
- Outbox Partition/Aggregate Key 使用 `ticketId`；
- 删除和保留遵守平台治理策略。

## 9. Migration 说明

`V008__assign_ticket.sql` 是参考迁移。应用前必须与 Phase 01–007 的真实 Schema 对齐，包括表名、Enum 策略、已有字段和 Migration Version。
