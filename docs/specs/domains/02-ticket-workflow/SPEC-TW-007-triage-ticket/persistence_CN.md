# SPEC-TW-007 — 持久化设计

## 1. Ticket 字段

在 `tickets` 上添加或确认以下字段：

| Column | Type | Nullability | 规则 |
|---|---|---:|---|
| `category_id` | UUID | 分诊前可为空 | 状态不是 `OPEN` 时必填 |
| `subcategory_id` | UUID | 可为空 | 必须属于 Category |
| `priority` | VARCHAR(16) | 分诊前可为空 | 四值 Enum |
| `support_queue_id` | UUID | 分诊前可为空 | 命令执行时必须是 Active Queue |
| `triaged_by` | UUID | 分诊前可为空 | 已认证 Actor |
| `triaged_at` | `TIMESTAMPTZ` | 分诊前可为空 | 服务端 UTC 时间 |
| `version` | BIGINT | 非空 | 通过乐观更新增加 |

如果 Phase 01 已设置默认 Priority，本迁移必须保留该值，但分诊命令仍须明确确认最终 Priority。

## 2. Catalog Tables

`ticket_categories` 和 `ticket_subcategories` 提供 Tenant 范围的 Active Catalog。Catalog Entry 以后停用时，历史 Ticket 仍保留 Identifier。禁止硬删除已被引用的 Catalog Entry。

## 3. 复用的横切 Tables

如果 Phase 01/02 已存在，应复用：

- `ticket_status_history`；
- `ticket_timeline`；
- `audit_log`；
- `outbox_events`；
- `idempotency_records`；
- `support_queues`；
- Queue Membership/Authorization Tables。

不能创建 Triage 专用 History 或 Outbox Table。

## 4. 必需写入

一次成功命令产生：

| Store | Record |
|---|---|
| `tickets` | 新分诊字段、`TRIAGED`、Version + 1 |
| `ticket_status_history` | `OPEN → TRIAGED`，Operation 为 `TRIAGE` |
| `ticket_timeline` | Type 为 `TICKET_TRIAGED`，Visibility 为 `INTERNAL` |
| `audit_log` | Action 为 `ticket.triage`，保存获准字段的 Before/After |
| `outbox_events` | Aggregate 为 `TICKET`，Event 为 `ticket.triaged.v1` |
| `idempotency_records` | Request Hash、最终 Response、ETag |

所有写入使用相同 `occurred_at`、Actor、Tenant 和 Correlation ID。

## 5. Constraints 与 Indexes

- Priority Check Constraint；
- Category/Subcategory Parent Foreign Key；
- Tenant-Aware Lookup Index；
- 用于已分诊队列查询的 Partial Index；
- Outbox `event_id` 唯一；
- 幂等范围唯一；
- 在兼容现有生命周期迁移的前提下添加 Status/Triage Field Consistency Check。

## 6. 迁移安全

参考迁移假设使用 PostgreSQL 和 Flyway。复制到服务前：

1. 与之前 Migration 对齐 Table/Column Name；
2. 删除 Phase 01/02 已引入的语句；
3. Status Constraint 或 PostgreSQL Enum 只能有一个负责迁移的文件；
4. 在 Empty Schema 和 Phase 02 Snapshot 上测试 Forward Migration；
5. 迁移后测试 Application Startup 和 Repository Mapping；
6. 共享环境中使用新的 Forward Corrective Migration，不能修改已经执行过的 Flyway 文件。

## 7. 持久化不变量

- `TRIAGED` Ticket 不能缺少 Category、Priority、Queue、Triager 或 Triage Time；
- `OPEN` Ticket 的分诊字段可以为空；
- Ticket Version 不能减少；
- History 只追加；
- Outbox Payload 与 Ticket State 必须一起提交；
- Audit 和 Timeline 不能指向其他 Tenant；
- 用户提供的 Reason 必须限制长度并安全编码。

