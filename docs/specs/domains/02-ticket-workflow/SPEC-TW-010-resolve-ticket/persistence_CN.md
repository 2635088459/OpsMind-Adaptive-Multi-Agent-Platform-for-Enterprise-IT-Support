# SPEC-TW-010 — 持久化设计

## 1. 迁移策略

Spec 目录提供参考迁移：

```text
V010__resolve_ticket.sql
```

真实 service 迁移应按当前 Flyway 序列命名：

```text
services/ticket-workflow-service/src/main/resources/db/migration/V016__resolve_ticket.sql
```

原因：真实代码库已使用 `V015__transition_ticket_status.sql`。

## 2. Ticket 表变化

`ticket.tickets` 需要新增或确认：

- `resolution_code VARCHAR(64)`；
- `resolution_summary TEXT`；
- `resolved_by VARCHAR(128)`；
- 继续使用已存在的 `resolved_at TIMESTAMPTZ`；
- 继续满足早期 schema 中 `RESOLVED` 对 `auto_close_due_at` 的要求，除非同一 migration 明确放宽该约束；
- 成功 resolve 时清理 `waiting_for_requester_since` 和 `approval_reference`；
- 保留 `current_support_user_id`。

## 3. 数据约束

数据库应防御以下不变量：

- `RESOLVED` 必须有 `resolved_at`、`resolved_by`、`resolution_code` 和 `resolution_summary`；
- `RESOLVED` 必须有 `current_support_user_id`；
- `RESOLVED` 不得保留 waiting metadata；
- `resolution_code` 必须属于受控枚举；
- `resolution_summary` trim 后长度为 10 到 5000。
- 如果保留 `auto_close_due_at` 约束，resolve command 必须根据本地策略设置该值；auto-close scheduler 本身不属于 SPEC-TW-010。

## 4. Resolution Cycle

复用已存在的 `ticket.ticket_resolution_cycles`。成功 resolve 必须把当前 cycle 标记为 completed/resolved，并保存：

- `resolved_at` / `completed_at`；
- `resolved_by` / `completed_by`；
- `resolution_code`；
- `resolution_summary` snapshot；
- resulting ticket version。

字段名应以现有表结构为准；如果表缺少 snapshot 字段，则在 `V016` 增加。

当前 `V003__create_ticket_resolution_cycles.sql` 的 `ck_resolution_cycle_resolved` 还要求 `root_cause_code` 和 `verification_id`。这属于早期冻结设计的更重验证模型，不在当前 Phase 03 / SPEC-TW-010 的 required input 内。`V016` 必须二选一：

- 放宽该 CHECK，只要求 `resolved_at`、`resolution_code`、`resolution_summary` 和 resolved actor；
- 或把 `rootCauseCode` 与 `verificationId` 提升为 API 必填项，并同步更新本文档、OpenAPI、AsyncAPI 和测试计划。

本 SPEC 默认采用第一种方案。

## 5. Status History

复用 `ticket.ticket_status_history`：

```text
transition_id = SM-010
reason_code = TICKET_RESOLVED
from_status = IN_PROGRESS
to_status = RESOLVED
```

`aggregate_version` 必须等于 Ticket 更新后的版本。

## 6. Timeline、Audit、Outbox

每次成功命令必须在同一事务写入：

- requester-safe timeline item；
- internal audit record；
- outbox row，event type 为 `ticket.resolved.v1`；
- finalized idempotency replay response。

失败命令不得写入成功 timeline、status history 或 outbox event。

## 7. Repository Update

推荐 Ticket 更新条件：

```sql
UPDATE ticket.tickets
SET status = 'RESOLVED',
    resolved_at = :resolved_at,
    resolved_by = :resolved_by,
    resolution_code = :resolution_code,
    resolution_summary = :resolution_summary,
    waiting_for_requester_since = NULL,
    approval_reference = NULL,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status = 'IN_PROGRESS'
  AND current_support_user_id IS NOT NULL
```

Resolution cycle update 必须带 `current_resolution_cycle_id` 和 incomplete status guard。

## 8. 索引

建议增加：

```sql
CREATE INDEX ix_tickets_resolved_at
    ON ticket.tickets (resolved_at DESC)
    WHERE status = 'RESOLVED';

CREATE INDEX ix_tickets_resolution_code
    ON ticket.tickets (resolution_code)
    WHERE resolution_code IS NOT NULL;
```

这些索引用于后续 close、auto-close 和运营分析，不实现报表功能。
