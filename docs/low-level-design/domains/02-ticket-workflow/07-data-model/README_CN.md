# OpsMind Ticket Workflow — 07 Data Model

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level PostgreSQL Data Model  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `01-domain-model/README_CN.md`、`02-business-invariants/README_CN.md`、`03-state-machine/README_CN.md`、`04-use-cases/README_CN.md`、`05-api-contracts/README_CN.md`、`06-event-contracts/README_CN.md`  
> **数据库：** PostgreSQL 18.x  
> **Migration：** Flyway  
> **建议路径：** `docs/low-level-design/domains/02-ticket-workflow/07-data-model/README_CN.md`

---

## 1. 文档目的

本文档将 Ticket Workflow 的领域模型和业务规则映射为 PostgreSQL 物理数据模型。

本文档冻结：

- PostgreSQL Schema
- Table Ownership
- Primary Key 与 Foreign Key
- Column Type
- Nullable 规则
- Check Constraint
- Unique Constraint
- Partial Unique Index
- Query Index
- Optimistic Locking
- Append-only History
- Resolution Cycle
- SLA Cycle
- Pending Action
- User Input Request
- Transactional Outbox
- Processed Event Store
- API Idempotency Store
- PII Classification
- Retention
- Flyway Migration 顺序

本文档不负责最终生成所有生产 DDL，但提供足够详细的 Draft DDL，后续代码实现不得随意偏离。

---

# 2. 数据所有权

Ticket Workflow Service 独占：

```text
ticket.*
```

只有 `ticket-workflow-service` 可以写入该 Schema。

其他服务：

- 不得直接 `INSERT`
- 不得直接 `UPDATE`
- 不得直接 `DELETE`
- 不得绕过 Ticket API 或 Event Contract

跨领域关联只保存外部 ID，例如：

```text
workflow_id
approval_id
tool_execution_id
verification_id
```

不建立跨服务数据库 Foreign Key。

---

# 3. Schema

```sql
CREATE SCHEMA IF NOT EXISTS ticket;
```

默认对象前缀：

```text
ticket.tickets
ticket.ticket_messages
ticket.ticket_status_history
...
```

建议数据库用户：

```text
ticket_app
ticket_migration
ticket_readonly
```

权限原则：

- `ticket_migration`：DDL
- `ticket_app`：业务 DML
- `ticket_readonly`：只读报表与受控调试
- 其他服务没有 Schema 写权限

---

# 4. 关键物理建模决策

## DM-001 ID 使用 PostgreSQL `uuid`

内部主键使用：

```text
uuid
```

由应用生成 UUIDv7 或其他有序 UUID。

原因：

- 跨服务安全生成
- 不暴露顺序业务量
- PostgreSQL 原生支持
- 可避免中心 ID 服务

用户可读 ID 使用：

```text
display_id VARCHAR(32)
```

例如：

```text
INC-2048
```

## DM-002 枚举使用 `VARCHAR + CHECK`

MVP 不使用 PostgreSQL ENUM。

原因：

- 修改 ENUM Migration 较笨重
- Java 与 JSON Contract 已有枚举
- `CHECK` 更容易版本化和回滚

## DM-003 时间统一使用 `TIMESTAMPTZ`

所有业务时间使用：

```sql
TIMESTAMPTZ
```

应用统一写入 UTC。

## DM-004 核心查询字段不存 JSONB

以下字段必须独立列：

```text
status
priority
category
subcategory
requester_id
active_workflow_id
team_id
created_at
updated_at
version
```

JSONB 仅用于：

- Outbox Payload
- Event Headers
- 小型非核心 Metadata
- Failure Detail 摘要

## DM-005 不使用软删除字段隐藏历史

Ticket、Status History、Resolution Cycle、Message 和 Audit Record 不通过普通 `deleted=true` 隐藏。

受控数据治理流程单独设计。

## DM-006 Timeline 不是 Source of Truth Table

MVP Timeline 由以下表组合：

```text
ticket_status_history
ticket_messages
ticket_assignment_history
ticket_category_history
ticket_escalation_history
ticket_resolution_cycles
ticket_sla_cycles
```

暂不创建 `ticket_timeline_entries` Source Table。

---

# 5. Table 总览

| Table | 类型 | 主要职责 |
|---|---|---|
| `tickets` | Aggregate Snapshot | Ticket 当前状态 |
| `ticket_messages` | Aggregate | 用户、Support、Agent Message |
| `ticket_status_history` | Append-only | 状态转换历史 |
| `ticket_category_history` | Append-only | 分类变更历史 |
| `ticket_assignment_history` | Append-only | Assignment 变更历史 |
| `ticket_user_input_requests` | Lifecycle Record | 等待用户请求 |
| `ticket_pending_actions` | Aggregate Child / History | Pending Action 与审批引用 |
| `ticket_resolution_cycles` | Cycle Record | 每轮 Resolution / Reopen |
| `ticket_sla_cycles` | Aggregate | 每轮 SLA |
| `ticket_escalation_history` | Append-only | Escalation 历史 |
| `ticket_automation_failures` | Append-only | 自动化失败记录 |
| `outbox_events` | Infrastructure | Transactional Outbox |
| `processed_events` | Infrastructure | Consumer Idempotency |
| `idempotency_records` | Infrastructure | HTTP Command Idempotency |

---

# 6. `ticket.tickets`

## 6.1 作用

保存 Ticket Aggregate 的当前 Snapshot。

它不是完整历史。

## 6.2 Column Design

| Column | Type | Null | 说明 |
|---|---|---:|---|
| `ticket_id` | uuid | no | Primary Key |
| `display_id` | varchar(32) | no | 用户可读 ID |
| `requester_id` | varchar(128) | no | Keycloak Subject / Identity Ref |
| `title` | varchar(200) | no | 敏感 |
| `initial_description` | text | no | 敏感 |
| `source` | varchar(32) | no | PORTAL / EMAIL / API / SYSTEM |
| `application_code` | varchar(64) | no | HOUSING_PORTAL 等 |
| `category` | varchar(64) | yes | 分类后写入 |
| `subcategory` | varchar(64) | yes | 分类后写入 |
| `priority` | varchar(16) | no | UNASSIGNED / LOW / MEDIUM / HIGH / CRITICAL |
| `status` | varchar(32) | no | Ticket Status |
| `current_team_id` | varchar(64) | yes | 当前 Support Team |
| `current_support_user_id` | varchar(128) | yes | 当前负责人 |
| `active_workflow_id` | varchar(64) | yes | 当前唯一 Workflow |
| `current_resolution_cycle_id` | uuid | no | 当前处理 Cycle |
| `auto_close_due_at` | timestamptz | yes | RESOLVED 后 72 小时 |
| `resolved_at` | timestamptz | yes | 当前 Cycle 解决时间 |
| `closed_at` | timestamptz | yes | 当前 Cycle 关闭时间 |
| `cancelled_at` | timestamptz | yes | 取消时间 |
| `cancel_reason_code` | varchar(64) | yes | 当前取消原因 |
| `close_reason_code` | varchar(64) | yes | 当前关闭原因 |
| `created_at` | timestamptz | no | 创建时间 |
| `updated_at` | timestamptz | no | 最后更新时间 |
| `version` | bigint | no | Optimistic Lock |
| `created_by_type` | varchar(32) | no | EMPLOYEE / SUPPORT / SERVICE |
| `created_by_id` | varchar(128) | no | Actor Reference |

## 6.3 Draft DDL

```sql
CREATE TABLE ticket.tickets (
    ticket_id UUID PRIMARY KEY,
    display_id VARCHAR(32) NOT NULL,
    requester_id VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    initial_description TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    application_code VARCHAR(64) NOT NULL,
    category VARCHAR(64),
    subcategory VARCHAR(64),
    priority VARCHAR(16) NOT NULL DEFAULT 'UNASSIGNED',
    status VARCHAR(32) NOT NULL,
    current_team_id VARCHAR(64),
    current_support_user_id VARCHAR(128),
    active_workflow_id VARCHAR(64),
    current_resolution_cycle_id UUID NOT NULL,
    auto_close_due_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancel_reason_code VARCHAR(64),
    close_reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,

    CONSTRAINT uq_tickets_display_id UNIQUE (display_id),

    CONSTRAINT ck_tickets_title_not_blank
        CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),

    CONSTRAINT ck_tickets_description_not_blank
        CHECK (char_length(btrim(initial_description)) BETWEEN 1 AND 10000),

    CONSTRAINT ck_tickets_source
        CHECK (source IN ('PORTAL', 'EMAIL', 'API', 'SYSTEM')),

    CONSTRAINT ck_tickets_priority
        CHECK (priority IN ('UNASSIGNED', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    CONSTRAINT ck_tickets_status
        CHECK (status IN (
            'NEW',
            'TRIAGING',
            'INVESTIGATING',
            'WAITING_FOR_USER',
            'WAITING_FOR_APPROVAL',
            'EXECUTING',
            'VERIFYING',
            'RESOLVED',
            'CLOSED',
            'ESCALATED',
            'FAILED',
            'CANCELLED'
        )),

    CONSTRAINT ck_tickets_version_nonnegative
        CHECK (version >= 0),

    CONSTRAINT ck_tickets_created_updated
        CHECK (updated_at >= created_at),

    CONSTRAINT ck_tickets_support_user_requires_team
        CHECK (
            current_support_user_id IS NULL
            OR current_team_id IS NOT NULL
        ),

    CONSTRAINT ck_tickets_resolved_fields
        CHECK (
            status <> 'RESOLVED'
            OR (
                resolved_at IS NOT NULL
                AND auto_close_due_at IS NOT NULL
            )
        ),

    CONSTRAINT ck_tickets_closed_fields
        CHECK (
            status <> 'CLOSED'
            OR (
                resolved_at IS NOT NULL
                AND closed_at IS NOT NULL
                AND close_reason_code IS NOT NULL
                AND active_workflow_id IS NULL
            )
        ),

    CONSTRAINT ck_tickets_cancelled_fields
        CHECK (
            status <> 'CANCELLED'
            OR (
                cancelled_at IS NOT NULL
                AND cancel_reason_code IS NOT NULL
            )
        )
);
```

## 6.4 核心索引

```sql
CREATE INDEX ix_tickets_requester_created
    ON ticket.tickets (requester_id, created_at DESC, ticket_id DESC);

CREATE INDEX ix_tickets_status_updated
    ON ticket.tickets (status, updated_at DESC, ticket_id DESC);

CREATE INDEX ix_tickets_queue_status_priority
    ON ticket.tickets (
        current_team_id,
        status,
        priority,
        updated_at DESC
    );

CREATE INDEX ix_tickets_assignee_status
    ON ticket.tickets (
        current_support_user_id,
        status,
        updated_at DESC
    )
    WHERE current_support_user_id IS NOT NULL;

CREATE INDEX ix_tickets_active_workflow
    ON ticket.tickets (active_workflow_id)
    WHERE active_workflow_id IS NOT NULL;

CREATE INDEX ix_tickets_auto_close_due
    ON ticket.tickets (auto_close_due_at, ticket_id)
    WHERE status = 'RESOLVED'
      AND auto_close_due_at IS NOT NULL;

CREATE INDEX ix_tickets_application_status
    ON ticket.tickets (application_code, status, created_at DESC);
```

## 6.5 Optimistic Update

```sql
UPDATE ticket.tickets
SET
    status = :new_status,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version;
```

更新行数必须为：

```text
1
```

否则返回：

```text
CONCURRENT_UPDATE
```

---

# 7. `ticket.ticket_messages`

## 7.1 作用

独立保存 TicketMessage Aggregate。

Message 创建后原则上不可修改。

## 7.2 Draft DDL

```sql
CREATE TABLE ticket.ticket_messages (
    message_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    author_type VARCHAR(32) NOT NULL,
    author_id VARCHAR(128) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    reply_to_message_id UUID,
    attachment_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    redacted_at TIMESTAMPTZ,
    redaction_reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_messages_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_ticket_messages_reply
        FOREIGN KEY (reply_to_message_id)
        REFERENCES ticket.ticket_messages(message_id),

    CONSTRAINT ck_ticket_messages_body
        CHECK (char_length(btrim(body)) BETWEEN 1 AND 20000),

    CONSTRAINT ck_ticket_messages_author_type
        CHECK (author_type IN (
            'EMPLOYEE',
            'IT_SUPPORT',
            'IT_ADMIN',
            'SYSTEM',
            'AGENT',
            'SERVICE'
        )),

    CONSTRAINT ck_ticket_messages_type
        CHECK (message_type IN (
            'USER_MESSAGE',
            'SUPPORT_MESSAGE',
            'SYSTEM_MESSAGE',
            'AGENT_QUESTION',
            'AGENT_SUMMARY',
            'RESOLUTION_INSTRUCTION'
        )),

    CONSTRAINT ck_ticket_messages_visibility
        CHECK (visibility IN (
            'REQUESTER_VISIBLE',
            'INTERNAL_SUPPORT_ONLY',
            'AUDIT_ONLY'
        )),

    CONSTRAINT ck_ticket_messages_attachments_array
        CHECK (jsonb_typeof(attachment_ids) = 'array'),

    CONSTRAINT ck_ticket_messages_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);
```

## 7.3 索引

```sql
CREATE INDEX ix_ticket_messages_ticket_created
    ON ticket.ticket_messages (ticket_id, created_at ASC, message_id ASC);

CREATE INDEX ix_ticket_messages_ticket_visibility_created
    ON ticket.ticket_messages (
        ticket_id,
        visibility,
        created_at ASC
    );

CREATE INDEX ix_ticket_messages_reply_to
    ON ticket.ticket_messages (reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;
```

## 7.4 Immutable 规则

普通应用数据库账号不得执行：

```sql
UPDATE ticket.ticket_messages
SET body = ...
```

允许的受控更新仅包括：

```text
redacted_at
redaction_reason_code
visibility correction
```

这些更新必须写 Audit Record。

---

# 8. `ticket.ticket_status_history`

## 8.1 作用

Append-only 保存所有状态变化。

## 8.2 Draft DDL

```sql
CREATE TABLE ticket.ticket_status_history (
    history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    transition_id VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    source_command_id VARCHAR(128),
    source_event_id VARCHAR(64),
    workflow_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_status_history_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT uq_ticket_status_history_version
        UNIQUE (ticket_id, aggregate_version),

    CONSTRAINT ck_ticket_status_history_version
        CHECK (aggregate_version >= 0)
);
```

## 8.3 索引

```sql
CREATE INDEX ix_ticket_status_history_ticket_time
    ON ticket.ticket_status_history (
        ticket_id,
        occurred_at ASC,
        history_id ASC
    );

CREATE INDEX ix_ticket_status_history_source_event
    ON ticket.ticket_status_history (source_event_id)
    WHERE source_event_id IS NOT NULL;
```

## 8.4 Append-only

应用账号不允许普通：

```text
UPDATE
DELETE
```

---

# 9. `ticket.ticket_category_history`

```sql
CREATE TABLE ticket.ticket_category_history (
    category_history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    old_category VARCHAR(64),
    old_subcategory VARCHAR(64),
    new_category VARCHAR(64) NOT NULL,
    new_subcategory VARCHAR(64),
    priority VARCHAR(16) NOT NULL,
    confidence NUMERIC(5,4),
    classification_source VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    workflow_id VARCHAR(64),
    source_event_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_category_history_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_ticket_category_confidence
        CHECK (
            confidence IS NULL
            OR confidence BETWEEN 0 AND 1
        )
);
```

索引：

```sql
CREATE INDEX ix_ticket_category_history_ticket_time
    ON ticket.ticket_category_history (
        ticket_id,
        changed_at ASC
    );
```

---

# 10. `ticket.ticket_assignment_history`

```sql
CREATE TABLE ticket.ticket_assignment_history (
    assignment_history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    old_team_id VARCHAR(64),
    old_support_user_id VARCHAR(128),
    new_team_id VARCHAR(64),
    new_support_user_id VARCHAR(128),
    assigned_by_type VARCHAR(32) NOT NULL,
    assigned_by_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_assignment_history_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_assignment_support_user_requires_team
        CHECK (
            new_support_user_id IS NULL
            OR new_team_id IS NOT NULL
        )
);
```

索引：

```sql
CREATE INDEX ix_ticket_assignment_history_ticket_time
    ON ticket.ticket_assignment_history (
        ticket_id,
        assigned_at ASC
    );
```

---

# 11. `ticket.ticket_user_input_requests`

## 11.1 作用

保存 `WAITING_FOR_USER` 的 Request Reference。

## 11.2 Draft DDL

```sql
CREATE TABLE ticket.ticket_user_input_requests (
    user_input_request_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    request_message_id UUID NOT NULL,
    response_message_id UUID,
    resume_status VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    answered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,

    CONSTRAINT fk_user_input_request_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_user_input_request_message
        FOREIGN KEY (request_message_id)
        REFERENCES ticket.ticket_messages(message_id),

    CONSTRAINT fk_user_input_response_message
        FOREIGN KEY (response_message_id)
        REFERENCES ticket.ticket_messages(message_id),

    CONSTRAINT uq_user_input_request_key
        UNIQUE (ticket_id, request_key),

    CONSTRAINT ck_user_input_resume_status
        CHECK (resume_status IN ('TRIAGING', 'INVESTIGATING')),

    CONSTRAINT ck_user_input_status
        CHECK (status IN ('OPEN', 'ANSWERED', 'CANCELLED', 'EXPIRED')),

    CONSTRAINT ck_user_input_answered
        CHECK (
            status <> 'ANSWERED'
            OR (
                response_message_id IS NOT NULL
                AND answered_at IS NOT NULL
            )
        )
);
```

## 11.3 一个 Ticket 最多一个 Open Request

```sql
CREATE UNIQUE INDEX uq_ticket_one_open_user_request
    ON ticket.ticket_user_input_requests (ticket_id)
    WHERE status = 'OPEN';
```

---

# 12. `ticket.ticket_pending_actions`

## 12.1 作用

保存 Pending Action、Approval 与 Tool Execution 引用。

记录保留历史，不通过删除表示结束。

## 12.2 Draft DDL

```sql
CREATE TABLE ticket.ticket_pending_actions (
    pending_action_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    approval_id VARCHAR(64),
    policy_decision_id VARCHAR(64),
    policy_decision VARCHAR(32),
    tool_execution_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    authorized_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    invalidation_reason_code VARCHAR(64),

    CONSTRAINT fk_pending_action_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT uq_pending_action_business_id
        UNIQUE (ticket_id, action_id),

    CONSTRAINT uq_pending_action_tool_execution
        UNIQUE (tool_execution_id),

    CONSTRAINT ck_pending_action_risk
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    CONSTRAINT ck_pending_action_policy_decision
        CHECK (
            policy_decision IS NULL
            OR policy_decision IN (
                'APPROVED',
                'AUTO_APPROVED',
                'REJECTED',
                'EXPIRED'
            )
        ),

    CONSTRAINT ck_pending_action_status
        CHECK (status IN (
            'PENDING_APPROVAL',
            'AUTHORIZED',
            'EXECUTING',
            'CONSUMED',
            'REJECTED',
            'EXPIRED',
            'INVALIDATED'
        ))
);
```

## 12.3 MVP 最多一个 Active Pending Action

```sql
CREATE UNIQUE INDEX uq_ticket_one_active_pending_action
    ON ticket.ticket_pending_actions (ticket_id)
    WHERE status IN (
        'PENDING_APPROVAL',
        'AUTHORIZED',
        'EXECUTING'
    );
```

## 12.4 索引

```sql
CREATE INDEX ix_pending_actions_approval
    ON ticket.ticket_pending_actions (approval_id)
    WHERE approval_id IS NOT NULL;

CREATE INDEX ix_pending_actions_workflow
    ON ticket.ticket_pending_actions (workflow_id, status);

CREATE INDEX ix_pending_actions_ticket_status
    ON ticket.ticket_pending_actions (ticket_id, status);
```

---

# 13. `ticket.ticket_resolution_cycles`

## 13.1 作用

保存每次创建和 Reopen 后的处理 Cycle。

Ticket 当前 Snapshot 指向：

```text
current_resolution_cycle_id
```

## 13.2 Draft DDL

```sql
CREATE TABLE ticket.ticket_resolution_cycles (
    resolution_cycle_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    cycle_number INTEGER NOT NULL,
    workflow_id VARCHAR(64),
    sla_cycle_id UUID,
    cycle_status VARCHAR(24) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    reopened_at TIMESTAMPTZ,
    reopen_reason_code VARCHAR(64),
    reopened_by_type VARCHAR(32),
    reopened_by_id VARCHAR(128),
    resolution_code VARCHAR(64),
    root_cause_code VARCHAR(64),
    resolution_summary TEXT,
    verification_id VARCHAR(64),
    resolution_attempt_id VARCHAR(64),
    resolved_by_type VARCHAR(32),
    resolved_by_id VARCHAR(128),
    close_reason_code VARCHAR(64),
    closed_by_type VARCHAR(32),
    closed_by_id VARCHAR(128),

    CONSTRAINT fk_resolution_cycle_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT uq_resolution_cycle_number
        UNIQUE (ticket_id, cycle_number),

    CONSTRAINT ck_resolution_cycle_number
        CHECK (cycle_number >= 1),

    CONSTRAINT ck_resolution_cycle_status
        CHECK (cycle_status IN (
            'ACTIVE',
            'RESOLVED',
            'CLOSED',
            'REOPENED',
            'CANCELLED'
        )),

    CONSTRAINT ck_resolution_cycle_resolved
        CHECK (
            cycle_status NOT IN ('RESOLVED', 'CLOSED', 'REOPENED')
            OR (
                resolved_at IS NOT NULL
                AND resolution_code IS NOT NULL
                AND root_cause_code IS NOT NULL
                AND verification_id IS NOT NULL
            )
        )
);
```

## 13.3 一个 Ticket 最多一个 Active Cycle

```sql
CREATE UNIQUE INDEX uq_ticket_one_active_resolution_cycle
    ON ticket.ticket_resolution_cycles (ticket_id)
    WHERE cycle_status = 'ACTIVE';
```

注意：

Ticket 进入 `RESOLVED` 后，Cycle 状态变为 `RESOLVED`，但仍是当前 Cycle。

`current_resolution_cycle_id` 负责标识当前 Cycle。

## 13.4 索引

```sql
CREATE INDEX ix_resolution_cycles_ticket_number
    ON ticket.ticket_resolution_cycles (
        ticket_id,
        cycle_number DESC
    );

CREATE INDEX ix_resolution_cycles_workflow
    ON ticket.ticket_resolution_cycles (workflow_id)
    WHERE workflow_id IS NOT NULL;

CREATE INDEX ix_resolution_cycles_verification
    ON ticket.ticket_resolution_cycles (verification_id)
    WHERE verification_id IS NOT NULL;
```

---

# 14. `ticket.ticket_sla_cycles`

## 14.1 作用

独立 TicketSla Aggregate。

## 14.2 Draft DDL

```sql
CREATE TABLE ticket.ticket_sla_cycles (
    sla_cycle_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    policy_id VARCHAR(64) NOT NULL,
    cycle_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_due_at TIMESTAMPTZ,
    resolution_due_at TIMESTAMPTZ,
    paused_at TIMESTAMPTZ,
    accumulated_paused_seconds BIGINT NOT NULL DEFAULT 0,
    breached_at TIMESTAMPTZ,
    met_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_sla_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_sla_resolution_cycle
        FOREIGN KEY (resolution_cycle_id)
        REFERENCES ticket.ticket_resolution_cycles(resolution_cycle_id),

    CONSTRAINT uq_sla_cycle_number
        UNIQUE (ticket_id, cycle_number),

    CONSTRAINT uq_sla_resolution_cycle
        UNIQUE (resolution_cycle_id),

    CONSTRAINT ck_sla_status
        CHECK (status IN (
            'ACTIVE',
            'PAUSED',
            'MET',
            'BREACHED',
            'CANCELLED'
        )),

    CONSTRAINT ck_sla_pause_seconds
        CHECK (accumulated_paused_seconds >= 0),

    CONSTRAINT ck_sla_time_order
        CHECK (
            resolution_due_at IS NULL
            OR resolution_due_at >= created_at
        ),

    CONSTRAINT ck_sla_version
        CHECK (version >= 0)
);
```

## 14.3 一个 Ticket 最多一个 Active SLA

```sql
CREATE UNIQUE INDEX uq_ticket_one_active_sla_cycle
    ON ticket.ticket_sla_cycles (ticket_id)
    WHERE status IN ('ACTIVE', 'PAUSED', 'BREACHED');
```

`BREACHED` 仍表示当前 Cycle 未完成，因此包含在 Active Unique Index 中。

## 14.4 索引

```sql
CREATE INDEX ix_sla_resolution_due
    ON ticket.ticket_sla_cycles (
        resolution_due_at,
        ticket_id
    )
    WHERE status IN ('ACTIVE', 'BREACHED');

CREATE INDEX ix_sla_response_due
    ON ticket.ticket_sla_cycles (
        response_due_at,
        ticket_id
    )
    WHERE status = 'ACTIVE';

CREATE INDEX ix_sla_ticket_cycle
    ON ticket.ticket_sla_cycles (
        ticket_id,
        cycle_number DESC
    );
```

---

# 15. `ticket.ticket_escalation_history`

```sql
CREATE TABLE ticket.ticket_escalation_history (
    escalation_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    comment TEXT,
    evidence_reference_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    automation_restricted BOOLEAN NOT NULL DEFAULT TRUE,
    escalated_by_type VARCHAR(32) NOT NULL,
    escalated_by_id VARCHAR(128) NOT NULL,
    source_event_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    escalated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_escalation_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_escalation_cycle
        FOREIGN KEY (resolution_cycle_id)
        REFERENCES ticket.ticket_resolution_cycles(resolution_cycle_id),

    CONSTRAINT ck_escalation_evidence_array
        CHECK (jsonb_typeof(evidence_reference_ids) = 'array')
);
```

索引：

```sql
CREATE INDEX ix_escalation_ticket_time
    ON ticket.ticket_escalation_history (
        ticket_id,
        escalated_at ASC
    );
```

---

# 16. `ticket.ticket_automation_failures`

```sql
CREATE TABLE ticket.ticket_automation_failures (
    failure_record_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    failure_reference_id VARCHAR(64) NOT NULL,
    failure_category VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    retryable BOOLEAN NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_event_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_automation_failure_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_automation_failure_cycle
        FOREIGN KEY (resolution_cycle_id)
        REFERENCES ticket.ticket_resolution_cycles(resolution_cycle_id),

    CONSTRAINT uq_automation_failure_reference
        UNIQUE (failure_reference_id),

    CONSTRAINT ck_automation_failure_retry_count
        CHECK (retry_count >= 0),

    CONSTRAINT ck_automation_failure_details
        CHECK (jsonb_typeof(details) = 'object')
);
```

禁止在 `details` 中保存完整 Stack Trace、Prompt 或 Secret。

---

# 17. `ticket.outbox_events`

## 17.1 作用

保证业务状态与 Integration Event 原子写入。

## 17.2 Draft DDL

```sql
CREATE TABLE ticket.outbox_events (
    outbox_id UUID PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version VARCHAR(16) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    trace_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128),
    data_classification VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_publish_error_code VARCHAR(64),
    last_publish_error_at TIMESTAMPTZ,
    locked_by VARCHAR(128),
    locked_at TIMESTAMPTZ,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),

    CONSTRAINT fk_outbox_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_outbox_classification
        CHECK (data_classification IN (
            'PUBLIC',
            'INTERNAL',
            'SENSITIVE'
        )),

    CONSTRAINT ck_outbox_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),

    CONSTRAINT ck_outbox_headers_object
        CHECK (jsonb_typeof(headers) = 'object'),

    CONSTRAINT ck_outbox_publish_attempts
        CHECK (publish_attempts >= 0)
);
```

## 17.3 Publisher 索引

```sql
CREATE INDEX ix_outbox_unpublished_available
    ON ticket.outbox_events (
        available_at,
        created_at,
        outbox_id
    )
    WHERE published_at IS NULL;

CREATE INDEX ix_outbox_locked
    ON ticket.outbox_events (locked_at)
    WHERE published_at IS NULL
      AND locked_at IS NOT NULL;

CREATE INDEX ix_outbox_ticket_created
    ON ticket.outbox_events (
        ticket_id,
        created_at ASC
    );
```

## 17.4 Publisher Claim Query

```sql
SELECT *
FROM ticket.outbox_events
WHERE published_at IS NULL
  AND available_at <= now()
  AND (
      locked_at IS NULL
      OR locked_at < now() - interval '5 minutes'
  )
ORDER BY created_at, outbox_id
FOR UPDATE SKIP LOCKED
LIMIT :batch_size;
```

---

# 18. `ticket.processed_events`

## 18.1 作用

Consumer 幂等和 Event Replay 审计。

## 18.2 Draft DDL

```sql
CREATE TABLE ticket.processed_events (
    consumer_name VARCHAR(128) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version VARCHAR(16) NOT NULL,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    payload_hash CHAR(64) NOT NULL,
    processing_result VARCHAR(32) NOT NULL,
    aggregate_version_after BIGINT,
    first_received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(64),

    PRIMARY KEY (consumer_name, event_id),

    CONSTRAINT fk_processed_event_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_processed_event_result
        CHECK (processing_result IN (
            'APPLIED',
            'DUPLICATE',
            'STALE',
            'REJECTED_BUSINESS_RULE'
        ))
);
```

## 18.3 索引

```sql
CREATE INDEX ix_processed_events_ticket_time
    ON ticket.processed_events (
        ticket_id,
        processed_at DESC
    );

CREATE INDEX ix_processed_events_type_time
    ON ticket.processed_events (
        event_type,
        processed_at DESC
    );

CREATE INDEX ix_processed_events_error
    ON ticket.processed_events (
        error_code,
        processed_at DESC
    )
    WHERE error_code IS NOT NULL;
```

## 18.4 同 EventId 不同 Payload

如果同一 Primary Key 已存在，但：

```text
payload_hash 不同
```

必须产生：

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
```

并进入 Security Review / DLQ。

---

# 19. `ticket.idempotency_records`

## 19.1 作用

保护 HTTP Command：

- Create Ticket
- Add Message
- Cancel
- Reopen
- Confirm Resolution
- Support Command

## 19.2 Draft DDL

```sql
CREATE TABLE ticket.idempotency_records (
    idempotency_record_id UUID PRIMARY KEY,
    actor_scope VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_idempotency_scope_key
        UNIQUE (actor_scope, idempotency_key),

    CONSTRAINT ck_idempotency_status
        CHECK (status IN (
            'IN_PROGRESS',
            'COMPLETED',
            'FAILED_RETRYABLE',
            'FAILED_FINAL'
        )),

    CONSTRAINT ck_idempotency_response_body
        CHECK (
            response_body IS NULL
            OR jsonb_typeof(response_body) = 'object'
        )
);
```

## 19.3 索引

```sql
CREATE INDEX ix_idempotency_expiry
    ON ticket.idempotency_records (expires_at);

CREATE INDEX ix_idempotency_resource
    ON ticket.idempotency_records (
        resource_type,
        resource_id
    )
    WHERE resource_id IS NOT NULL;

CREATE INDEX ix_idempotency_in_progress
    ON ticket.idempotency_records (
        created_at
    )
    WHERE status = 'IN_PROGRESS';
```

## 19.4 TTL

MVP 建议：

```text
Create Ticket: 24 hours
State-changing Command: 24 hours
Message creation: 24 hours
```

清理 Job 只能删除：

```text
expires_at < now()
且 status != IN_PROGRESS
```

---

# 20. 外键策略

## 20.1 Schema 内部 Foreign Key

允许：

```text
message.ticket_id → tickets.ticket_id
history.ticket_id → tickets.ticket_id
sla.ticket_id → tickets.ticket_id
```

## 20.2 跨服务 ID 不建 Foreign Key

以下字段不建数据库 Foreign Key：

```text
workflow_id
approval_id
tool_execution_id
verification_id
requester_id
support_user_id
team_id
```

原因：

- 数据由其他服务拥有
- 服务生命周期独立
- 避免跨 Schema 耦合
- 避免分布式事务假象

---

# 21. 事务映射

## 21.1 Create Ticket

```text
INSERT tickets
INSERT resolution_cycles
INSERT sla_cycles
INSERT status_history
INSERT outbox_events
INSERT / UPDATE idempotency_records
COMMIT
```

## 21.2 Status Transition

```text
UPDATE tickets with expected version
INSERT status_history
INSERT optional domain history
INSERT outbox_events
INSERT processed_events, if event-driven
COMMIT
```

## 21.3 User Reply Resume

```text
INSERT ticket_messages
UPDATE user_input_requests
UPDATE ticket_sla_cycles
UPDATE tickets
INSERT status_history
INSERT outbox_events
UPDATE idempotency_records
COMMIT
```

## 21.4 Verification Success

```text
UPDATE resolution_cycles
UPDATE sla_cycles
UPDATE tickets
INSERT status_history
INSERT outbox ticket.resolved
INSERT processed_events
COMMIT
```

---

# 22. 不变量到数据库约束映射

| Business Rule | Database Support |
|---|---|
| Display ID 唯一 | `UNIQUE(display_id)` |
| Ticket Version 非负 | CHECK |
| 一个 Open User Request | Partial Unique Index |
| 一个 Active Pending Action | Partial Unique Index |
| 一个 Active SLA Cycle | Partial Unique Index |
| Cycle Number 唯一 | `UNIQUE(ticket_id, cycle_number)` |
| Event 只处理一次 | PK `(consumer_name, event_id)` |
| Event ID 唯一发布 | `UNIQUE(event_id)` |
| 同 Scope 幂等 Key 唯一 | Unique Constraint |
| History Version 唯一 | `UNIQUE(ticket_id, aggregate_version)` |
| Support User 必须有 Team | CHECK |
| CLOSED 必须有 closedAt | CHECK |
| CANCELLED 必须有取消原因 | CHECK |

数据库 Constraint 不能替代 Domain Guard，但提供最后一道保护。

---

# 23. 查询模型

## 23.1 Employee Ticket List

使用：

```text
tickets(requester_id, created_at DESC, ticket_id DESC)
```

## 23.2 Support Queue

使用：

```text
tickets(current_team_id, status, priority, updated_at DESC)
```

## 23.3 Auto-close

使用 Partial Index：

```text
status = RESOLVED
auto_close_due_at <= now
```

## 23.4 Timeline

按 Ticket 分别读取：

```text
status_history
messages
assignment_history
category_history
escalation_history
resolution_cycles
sla_cycles
```

Application Query Layer 合并、按时间排序、按角色过滤。

MVP 暂不引入 Elasticsearch。

---

# 24. Cursor Pagination

Cursor 建议编码：

```json
{
  "createdAt": "2026-07-23T16:30:00Z",
  "ticketId": "01J..."
}
```

Query：

```sql
SELECT ...
FROM ticket.tickets
WHERE requester_id = :requester_id
  AND (
      created_at < :cursor_created_at
      OR (
          created_at = :cursor_created_at
          AND ticket_id < :cursor_ticket_id
      )
  )
ORDER BY created_at DESC, ticket_id DESC
LIMIT :limit;
```

Cursor 对客户端必须是 Opaque。

---

# 25. PII Classification

| Table / Column | 分类 |
|---|---|
| `tickets.requester_id` | Sensitive |
| `tickets.title` | Sensitive |
| `tickets.initial_description` | Sensitive |
| `ticket_messages.body` | Sensitive |
| `created_by_id` | Sensitive |
| `support_user_id` | Internal / Sensitive |
| `resolution_summary` | Sensitive |
| `outbox.payload` | 根据 `data_classification` |
| `processed_events.payload_hash` | Internal |
| `status` / `category` | Internal |
| `ticket_id` / `display_id` | Internal |

规则：

- PII 不进入 Metrics Label。
- 普通 Log 不输出 Message Body 或 Description。
- Outbox Payload 必须在写入前脱敏。
- 数据库 Backup 视为 Sensitive。
- 本地 Demo 数据使用 Fake Identity。

---

# 26. Encryption 与 Secret

## At Rest

依赖 PostgreSQL Volume / Cloud Disk Encryption。

## In Transit

```text
TLS
```

## Application-level Encryption

MVP 不对每个 Ticket Field 单独加密。

未来如存储更敏感身份信息，可对选定字段使用 Envelope Encryption。

## Secret

Secret 不属于该 Data Model。

以下数据禁止落表：

```text
password
access token
refresh token
API key
private key
raw Duo admin credential
```

---

# 27. Retention

MVP 建议：

| Data | Retention |
|---|---|
| Ticket Core | 2 years |
| Messages | 2 years |
| Status / Assignment / Category History | 2 years |
| Resolution / SLA Cycles | 2 years |
| Outbox Published Records | 30 days |
| Processed Events | 90 days |
| Idempotency Records | 24 hours after expiry |
| Automation Failure Detail | 90 days，摘要可更久 |
| DLQ Message | 30 days，需人工处理 |

Retention Job 必须：

- 分批删除
- 避免长事务
- 记录删除量
- 保护 Legal Hold
- 不破坏引用完整性

---

# 28. Partitioning 决策

MVP 不对业务表进行 Partitioning。

原因：

- 14 天 MVP 数据量有限
- 降低 Migration 和 Query 复杂度
- 优先验证业务正确性

未来候选：

```text
outbox_events 按 created_at 月分区
processed_events 按 processed_at 月分区
status_history 按 occurred_at 月分区
```

触发评估条件：

```text
单表超过 50M rows
Vacuum 压力显著
Retention 删除过慢
```

---

# 29. Flyway Migration 顺序

建议：

```text
V001__create_ticket_schema.sql
V002__create_tickets.sql
V003__create_resolution_cycles.sql
V004__create_sla_cycles.sql
V005__create_ticket_messages.sql
V006__create_status_and_domain_history.sql
V007__create_user_input_requests.sql
V008__create_pending_actions.sql
V009__create_outbox_events.sql
V010__create_processed_events.sql
V011__create_idempotency_records.sql
V012__create_indexes.sql
V013__grant_ticket_permissions.sql
```

由于 `tickets.current_resolution_cycle_id` 与 `resolution_cycles.ticket_id` 形成创建顺序依赖，推荐：

1. 创建 `tickets` 时先不加该 Foreign Key。
2. 创建 `resolution_cycles`。
3. 插入数据模型初始化逻辑后再增加 FK，或保留为受控应用引用。

MVP 推荐：

```text
current_resolution_cycle_id 保留 NOT NULL
但暂不建立反向 FK
```

避免循环 Foreign Key。

---

# 30. JPA Mapping 建议

## Ticket

```text
@Entity
@Table(schema = "ticket", name = "tickets")
@Version
private long version;
```

## Value Object

建议使用：

```text
@Embeddable
AttributeConverter
```

但 Domain Object 不应被 JPA Annotation 完全绑死。

推荐分离：

```text
Domain Ticket
TicketJpaEntity
TicketPersistenceMapper
```

## JSONB

使用：

```text
String / JsonNode
或 Hibernate JSON Mapping
```

仅用于 Infrastructure Record。

---

# 31. 数据访问分层

```text
Domain Repository
→ TicketRepository

Infrastructure Repository
→ JpaTicketRepository
→ TicketPersistenceAdapter
```

Query Side：

```text
TicketQueryRepository
TicketTimelineQueryRepository
SupportQueueQueryRepository
```

Query Repository 可以使用：

```text
JdbcTemplate
Spring Data Projection
```

不要求加载完整 Aggregate。

---

# 32. Backup 与 Restore

必须验证：

- PostgreSQL Backup 可恢复
- UUID、Sequence 和 Constraint 保留
- Outbox 未发布记录不丢失
- Processed Event Store 与业务数据一致
- Restore 后重复 Event 仍然幂等

MVP 至少提供：

```text
pg_dump
restore script
documented recovery drill
```

---

# 33. 数据完整性监控

定期检查：

```text
Ticket 没有 Current Resolution Cycle
RESOLVED Ticket 没有 Resolution Data
CLOSED Ticket 没有 closedAt
WAITING_FOR_APPROVAL 没有 Active Pending Action
WAITING_FOR_USER 没有 Open User Request
Active Workflow 与当前 Cycle 不一致
Outbox 未发布超过阈值
Processed Event 与 Ticket Version 异常
```

建议生成 Metric：

```text
ticket_data_integrity_violation_total
```

---

# 34. 关键 Integration Tests

```text
shouldCreateTicketWithResolutionAndSlaCycleAtomically
shouldEnforceUniqueDisplayId
shouldAllowOnlyOneOpenUserInputRequest
shouldAllowOnlyOneActivePendingAction
shouldAllowOnlyOneActiveSlaCycle
shouldRejectSupportUserWithoutTeam
shouldEnforceOptimisticLock
shouldRollbackHistoryWhenTicketUpdateFails
shouldRollbackOutboxWhenTicketUpdateFails
shouldRollbackTicketWhenOutboxInsertFails
shouldDeduplicateProcessedEvent
shouldDetectReusedEventIdWithDifferentPayload
shouldClaimOutboxRowsWithSkipLocked
shouldQueryRequesterTicketsWithCursor
shouldQuerySupportQueueUsingCompositeIndex
shouldPreservePreviousResolutionCycleAfterReopen
```

---

# 35. 被拒绝的数据模型

## 35.1 所有数据放进 `tickets.payload JSONB`

拒绝，因为无法可靠索引、约束和维护。

## 35.2 Message 作为 Ticket Row 内 JSON Array

拒绝，因为无限增长并制造并发冲突。

## 35.3 Status History 作为 Ticket Aggregate List

拒绝，因为每次加载都会持续变大。

## 35.4 删除旧 Resolution 覆盖新 Resolution

拒绝，因为 Reopen 必须保留历史 Cycle。

## 35.5 Redis 作为 Ticket Source of Truth

拒绝。PostgreSQL 是业务 Source of Truth。

## 35.6 其他服务直接写 Ticket Schema

拒绝，因为破坏数据所有权和业务不变量。

## 35.7 使用数据库 Trigger 执行完整状态机

拒绝。状态机属于 Domain Layer；数据库只提供 Constraint 与最后防线。

---

# 36. 验收标准

- [x] Schema 与 Ownership 已定义。
- [x] Ticket Snapshot Table 已定义。
- [x] Message Aggregate Table 已定义。
- [x] Status、Category、Assignment History 已定义。
- [x] User Input Request 已定义。
- [x] Pending Action 已定义。
- [x] Resolution Cycle 已定义。
- [x] SLA Cycle 已定义。
- [x] Escalation 与 Failure Record 已定义。
- [x] Outbox 已定义。
- [x] Processed Event Store 已定义。
- [x] Idempotency Store 已定义。
- [x] PK、FK、Check、Unique 和 Partial Index 已定义。
- [x] Query Index 与 Cursor Pagination 已定义。
- [x] PII、Retention 和 Encryption 已定义。
- [x] Flyway Migration 顺序已定义。
- [x] JPA 与 Repository Mapping 建议已定义。
- [x] Integration Test 要求已定义。

---

# 37. 下一步

下一份文档：

```text
08-transaction-and-outbox/README_CN.md
08-transaction-and-outbox/README_EN.md
```

该文档将详细定义：

- 每个 Use Case 的事务边界
- Outbox Publisher Lifecycle
- Publisher Confirm
- Lock Claim
- Retry
- Crash Recovery
- Business Update、History、Processed Event 与 Outbox 的原子关系
