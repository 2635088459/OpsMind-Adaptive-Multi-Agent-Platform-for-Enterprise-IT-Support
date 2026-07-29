# Phase 03 — 工单生命周期与负责人管理

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：03
>
> Specs：`SPEC-TW-007` ～ `SPEC-TW-011`
>
> 前置条件：Phase 01 与 Phase 02 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 01 已支持创建工单，Phase 02 已支持查询工单、查看请求人的工单列表、添加消息、查询支持队列以及查看时间线。

Phase 03 的目标是补齐 Ticket Workflow 的 command side，使支持人员和自动化 Agent 能够安全地：

- 对工单进行分诊；
- 分配、重新分配或取消分配负责人；
- 按明确的状态机推进工单；
- 保存结构化解决方案并标记工单已解决；
- 关闭工单，或在问题复发时重新打开工单；
- 为所有写操作生成一致的时间线、审计记录和领域事件；
- 通过幂等控制和乐观锁避免重复请求及并发覆盖。

Phase 03 完成后，系统应能演示一个工单从创建、分诊、分配、处理、等待、解决、关闭到重新打开的完整生命周期。

## 2. 业务价值

没有生命周期和负责人控制时，Ticket 只是可查询的记录，无法成为可执行、可追踪的 IT 支持工作单。

Phase 03 将带来以下能力：

- 明确每个工单当前由谁负责；
- 阻止无效或越权的状态变更；
- 保留完整的 ownership 与 status history；
- 为 SLA、审批、通知和 Agent 自动修复提供可靠状态；
- 支持人工 Support Agent 与 AI Agent 在相同规则下协作；
- 为后续自动化提供可验证、可审计的 command boundary。

## 3. 范围

### 3.1 本 Phase 包含

- 工单分类、子分类、优先级和支持队列；
- 分诊人及分诊时间；
- 分配、重新分配与取消分配；
- 工单状态机及合法转换验证；
- 等待用户和等待审批状态；
- resolution summary、resolution code 与 resolution cycle；
- 关闭与重新打开；
- ownership history、status history 与 ticket timeline；
- audit log；
- transactional outbox domain events；
- idempotency；
- optimistic locking / version check；
- RBAC、输入验证、错误模型和可观测性；
- 单元测试、集成测试、契约测试和端到端测试。

### 3.2 本 Phase 不包含

- 自动分类或自动优先级模型；
- SLA 计时与违约升级引擎；
- 审批请求的完整业务流程；
- 邮件、Slack 或移动端通知；
- Agent 工具执行与自动修复；
- 知识库检索和解决方案推荐；
- 跨 Ticket 的 Problem、Incident 或 Change Management；
- 报表与分析看板。

这些能力可消费 Phase 03 产生的状态和事件，但不属于本 Phase 的实现边界。

## 4. 参与角色与权限

| 角色 | 主要权限 |
|---|---|
| Requester | 查看自己的工单和时间线；不能分诊、分配、解决或关闭 |
| Support Agent | 在授权队列内分诊、接单、推进状态和解决工单 |
| Support Lead | 跨授权队列分配、重新分配、取消分配、关闭和重新打开 |
| Automation Agent | 仅使用授予的 service identity 权限执行明确允许的操作 |
| System | 写入时间线、审计记录、历史记录和 outbox event |

所有权限必须在服务端验证，不能依赖前端隐藏按钮。

## 5. 统一生命周期模型

### 5.1 持久化状态

```text
OPEN
TRIAGED
ASSIGNED
IN_PROGRESS
WAITING_FOR_USER
WAITING_FOR_APPROVAL
RESOLVED
CLOSED
```

`REOPENED` 不作为长期持久化状态。重新打开是一项业务操作和领域事件；成功后 Ticket 进入 `IN_PROGRESS`，并创建新的 resolution cycle。这样可以避免一个只有瞬时意义的状态增加查询和状态机复杂度。

### 5.2 主流程

```text
OPEN
  → TRIAGED
  → ASSIGNED
  → IN_PROGRESS
  → WAITING_FOR_USER / WAITING_FOR_APPROVAL
  → IN_PROGRESS
  → RESOLVED
  → CLOSED
```

重新打开：

```text
RESOLVED → IN_PROGRESS
CLOSED   → IN_PROGRESS
```

### 5.3 状态转换矩阵

| 当前状态 | 允许的目标状态 | 触发操作 |
|---|---|---|
| `OPEN` | `TRIAGED` | Triage |
| `TRIAGED` | `ASSIGNED` | Assign |
| `ASSIGNED` | `TRIAGED` | Unassign |
| `ASSIGNED` | `IN_PROGRESS` | Start Work |
| `IN_PROGRESS` | `WAITING_FOR_USER` | Wait for User |
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | Wait for Approval |
| `IN_PROGRESS` | `RESOLVED` | Resolve |
| `WAITING_FOR_USER` | `IN_PROGRESS` | Resume Work |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Resume Work |
| `RESOLVED` | `CLOSED` | Close |
| `RESOLVED` | `IN_PROGRESS` | Reopen |
| `CLOSED` | `IN_PROGRESS` | Reopen |

未出现在矩阵中的转换默认非法。例如：

- `OPEN → RESOLVED`
- `TRIAGED → CLOSED`
- `CLOSED → WAITING_FOR_USER`
- `WAITING_FOR_APPROVAL → RESOLVED`

## 6. Phase 03 的五个 SPEC

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-007` | Triage Ticket | 设置分类、优先级和支持队列，并完成初步分诊 |
| 2 | `SPEC-TW-008` | Assign Ticket | 分配、重新分配或取消分配负责人 |
| 3 | `SPEC-TW-009` | Transition Ticket Status | 执行一般状态转换并拒绝非法转换 |
| 4 | `SPEC-TW-010` | Resolve Ticket | 保存解决方案并结束当前 resolution cycle |
| 5 | `SPEC-TW-011` | Close and Reopen Ticket | 关闭已解决工单，或创建新的处理周期 |

## 7. SPEC-TW-007 — Triage Ticket

### 7.1 目标

将一个 `OPEN` Ticket 转换为已经完成初步判断、可以进入支持队列并等待分配的 `TRIAGED` Ticket。

### 7.2 必须实现

- 设置 `category`；
- 可选设置 `subcategory`；
- 设置 `priority`；
- 设置 `supportQueueId`；
- 保存 `triagedBy` 和 `triagedAt`；
- 验证分类、优先级和队列是否有效；
- 验证操作者是否有目标队列权限；
- 仅允许 `OPEN → TRIAGED`；
- 更新时间线、审计日志和版本号；
- 在同一事务中写入 outbox event。

### 7.3 领域事件

```text
ticket.triaged.v1
```

### 7.4 完成结果

Ticket 具有明确的业务分类、优先级和支持队列，并可进入负责人分配流程。

## 8. SPEC-TW-008 — Assign Ticket

### 8.1 目标

为 Ticket 建立可追踪的 ownership，并支持负责人变化。

### 8.2 必须实现

- 将 `TRIAGED` Ticket 分配给有效的 Support Agent；
- 首次分配后执行 `TRIAGED → ASSIGNED`；
- 在不改变当前工作状态的情况下重新分配负责人；
- 允许授权角色取消分配；
- `ASSIGNED` 状态取消分配后回到 `TRIAGED`；
- 处于 `IN_PROGRESS` 或 waiting 状态时，取消分配必须被拒绝，除非先通过合法状态操作退回可分配状态；
- 验证 assignee 存在、处于 active 状态且具有队列权限；
- 保存 ownership history，包括前负责人、新负责人、原因、操作者和时间；
- 支持 `expectedVersion` / `If-Match` 乐观锁；
- 更新时间线、审计日志和 outbox。

### 8.3 领域事件

```text
ticket.assigned.v1
ticket.reassigned.v1
ticket.unassigned.v1
```

## 9. SPEC-TW-009 — Transition Ticket Status

### 9.1 目标

提供一个受状态机约束的通用命令，用于推进处理中和等待中的 Ticket。

### 9.2 支持的转换

- `ASSIGNED → IN_PROGRESS`
- `IN_PROGRESS → WAITING_FOR_USER`
- `IN_PROGRESS → WAITING_FOR_APPROVAL`
- `WAITING_FOR_USER → IN_PROGRESS`
- `WAITING_FOR_APPROVAL → IN_PROGRESS`

分诊、分配、解决、关闭和重新打开必须继续使用各自的专用命令，不能通过通用状态接口绕过业务规则。

### 9.3 必须实现

- 集中维护状态转换规则；
- 验证当前状态与目标状态；
- 要求保存 `reason`；
- 等待用户时可保存 `waitingForRequesterSince`；
- 等待审批时可保存 `approvalReference`；
- 恢复处理时清理对应 waiting metadata；
- 验证 Ticket 存在负责人后才能进入 `IN_PROGRESS`；
- 拒绝通过该接口直接进入 `RESOLVED` 或 `CLOSED`；
- 保存 status history；
- 支持幂等和 optimistic locking；
- 更新时间线、审计日志和 outbox。

### 9.4 领域事件

```text
ticket.status-changed.v1
```

## 10. SPEC-TW-010 — Resolve Ticket

### 10.1 目标

将“解决”建模为带结构化业务数据的独立操作，而不是普通状态修改。

### 10.2 必须实现

- 仅允许 `IN_PROGRESS → RESOLVED`；
- 要求非空 `resolutionSummary`；
- 要求有效 `resolutionCode`；
- 保存 `resolvedBy` 和 `resolvedAt`；
- 完成当前 resolution cycle；
- 清理 waiting metadata；
- 保留当前负责人，便于复核和后续关闭；
- 同一 idempotency key 的重复请求返回首次成功结果；
- 更新时间线、status history、审计日志和 outbox。

### 10.3 建议 resolution code

```text
FIXED
WORKAROUND_PROVIDED
DUPLICATE
REQUEST_FULFILLED
NOT_REPRODUCIBLE
USER_ERROR
NO_ACTION_REQUIRED
```

### 10.4 领域事件

```text
ticket.resolved.v1
```

## 11. SPEC-TW-011 — Close and Reopen Ticket

### 11.1 Close

- 仅允许 `RESOLVED → CLOSED`；
- 保存 `closedBy`、`closedAt` 和 `closeReason`；
- 关闭后 Ticket 不再接受普通状态转换；
- 更新时间线、status history、审计日志和 outbox。

### 11.2 Reopen

- 允许 `RESOLVED → IN_PROGRESS`；
- 允许 `CLOSED → IN_PROGRESS`；
- 要求非空 `reopenReason`；
- 创建新的 resolution cycle；
- 清空上一周期的当前 resolution 字段，但保留历史快照；
- 保留原负责人；如果原负责人已失效，则要求重新分配后再开始处理；
- 记录 reopen count；
- 更新时间线、status history、审计日志和 outbox。

### 11.3 领域事件

```text
ticket.closed.v1
ticket.reopened.v1
```

## 12. API 命令边界

建议的 HTTP 接口：

```text
POST /api/v1/tickets/{ticketId}/triage
POST /api/v1/tickets/{ticketId}/assignments
POST /api/v1/tickets/{ticketId}/reassignments
DELETE /api/v1/tickets/{ticketId}/assignment
POST /api/v1/tickets/{ticketId}/status-transitions
POST /api/v1/tickets/{ticketId}/resolution
POST /api/v1/tickets/{ticketId}/closure
POST /api/v1/tickets/{ticketId}/reopen
```

所有写请求应支持：

```text
Authorization: Bearer <token>
Idempotency-Key: <uuid>
If-Match: "<ticket-version>"
X-Correlation-Id: <uuid>
```

推荐成功返回：

```text
200 OK      更新已有 Ticket
201 Created 创建 assignment、resolution cycle 等子资源
```

推荐错误：

```text
400 INVALID_REQUEST
401 UNAUTHENTICATED
403 FORBIDDEN
404 TICKET_NOT_FOUND
409 INVALID_STATUS_TRANSITION
409 IDEMPOTENCY_CONFLICT
412 VERSION_MISMATCH
422 BUSINESS_RULE_VIOLATION
```

## 13. 数据模型变化

### 13.1 Ticket 聚合新增或确认字段

```text
status
category
subcategory
priority
support_queue_id
assignee_id
triaged_by
triaged_at
resolved_by
resolved_at
closed_by
closed_at
reopen_count
version
updated_at
```

### 13.2 新增历史实体

```text
ticket_assignment_history
ticket_status_history
ticket_resolution_cycle
```

每条历史记录至少应包含：

- `ticketId`
- before / after value
- `reason`
- `actorType`
- `actorId`
- `occurredAt`
- `correlationId`

## 14. 事务与一致性

每个命令必须在一个本地数据库事务中完成：

1. 加载 Ticket 与当前版本；
2. 验证权限和业务规则；
3. 修改 Ticket 聚合；
4. 写入对应 history；
5. 写入 timeline entry；
6. 写入 audit record；
7. 写入 outbox event；
8. 提交事务。

事件发布失败不能回滚已经提交的业务事务；outbox publisher 应在事务提交后重试发布。

## 15. 幂等与并发控制

- 所有 command endpoint 使用 `Idempotency-Key`；
- 相同 key、相同请求必须返回首次结果；
- 相同 key、不同请求 payload 必须返回 `409 IDEMPOTENCY_CONFLICT`；
- Ticket 使用单调递增的 `version`；
- 客户端通过 `If-Match` 或 `expectedVersion` 提交预期版本；
- 版本不匹配返回 `412 VERSION_MISMATCH`；
- 成功写入后返回新的 `ETag`；
- 不允许 last-write-wins 静默覆盖负责人或状态。

## 16. 时间线、审计与事件共同标准

每个成功命令必须生成：

- 用户可见或支持人员可见的 timeline entry；
- 不可变 audit record；
- 对应的 domain event；
- correlation ID；
- actor identity；
- before / after snapshot 或必要差异；
- 服务端时间戳。

失败命令不得生成业务成功事件，但安全相关失败可以进入安全审计日志。

## 17. 安全要求

- 从认证 token 获取 actor identity，不接受客户端传入的操作者身份；
- 使用 RBAC 与 queue-level authorization；
- Automation Agent 必须使用独立 service identity；
- resolution summary、reason 等自由文本必须限制长度并进行安全处理；
- 日志和事件不得包含 access token、密码或其他 secret；
- 对跨队列操作、关闭和重开执行更严格权限校验；
- 所有拒绝必须返回稳定、可测试的错误代码。

## 18. 可观测性

### 18.1 Metrics

```text
ticket_triage_total
ticket_assignment_total
ticket_status_transition_total
ticket_resolution_total
ticket_closure_total
ticket_reopen_total
ticket_command_failure_total
ticket_version_conflict_total
ticket_command_duration_seconds
```

### 18.2 Structured Logs

至少包含：

```text
ticketId
commandName
actorId
actorType
fromStatus
toStatus
result
errorCode
correlationId
durationMs
```

### 18.3 Tracing

HTTP command、数据库事务与 outbox publish 应共享同一个 trace / correlation context。

## 19. 测试策略

### 19.1 Unit Tests

- 每一条合法状态转换；
- 每一条非法状态转换；
- 分诊字段验证；
- assignee 和 queue 权限验证；
- resolve、close 和 reopen 业务规则；
- resolution cycle 创建与完成；
- 幂等规则；
- optimistic locking。

### 19.2 Integration Tests

- API → application → domain → persistence 完整链路；
- Ticket、history、timeline、audit 和 outbox 原子提交；
- 事务回滚不产生部分数据；
- 并发更新只有一个请求成功；
- RBAC 和 queue authorization；
- outbox publisher 重试。

### 19.3 Contract Tests

- 请求与响应 schema；
- 稳定错误代码；
- event name、version 与 payload；
- `ETag`、`If-Match` 和 `Idempotency-Key` 行为。

### 19.4 End-to-End Scenario

```text
Create Ticket
→ Get Ticket
→ Triage
→ Assign
→ Start Work
→ Wait for User
→ Resume Work
→ Resolve
→ Close
→ Reopen
→ Resolve Again
→ Close Again
→ Verify Timeline and History
```

## 20. 实施顺序

```text
SPEC-TW-007 Triage Ticket
        ↓
SPEC-TW-008 Assign Ticket
        ↓
SPEC-TW-009 Transition Ticket Status
        ↓
SPEC-TW-010 Resolve Ticket
        ↓
SPEC-TW-011 Close and Reopen Ticket
```

每个 SPEC 都应作为独立 vertical slice 完成：

```text
README / Acceptance Criteria
→ API Contract
→ Domain Rules
→ Persistence Migration
→ Application Handler
→ Timeline / Audit / Outbox
→ Tests
→ Documentation
```

在当前 SPEC 的验收标准全部通过前，不进入下一个 SPEC。

## 21. Phase 完成标准

Phase 03 只有在以下条件全部满足时才能标记完成：

- `SPEC-TW-007`～`SPEC-TW-011` 全部实现；
- 状态机由单一规则源维护；
- 所有非法转换均被拒绝；
- ownership 和 status history 可查询并与 Ticket 当前状态一致；
- resolve、close 和 reopen 使用专用业务命令；
- 所有写操作支持幂等和乐观锁；
- timeline、audit 与 outbox 在同一事务内写入；
- RBAC 与 queue-level authorization 测试通过；
- 单元、集成、契约和端到端测试通过；
- API 和事件文档完成；
- 演示脚本可以完成两次 resolution cycle。

## 22. 建议目录

```text
docs/
├── implementation-plans/
│   └── domains/
│       └── 02-ticket-workflow/
│           ├── phase-03-ticket-lifecycle-and-ownership_CN.md
│           └── phase-03-ticket-lifecycle-and-ownership_EN.md
└── specs/
    └── ticket-workflow/
        ├── SPEC-TW-007-triage-ticket/
        ├── SPEC-TW-008-assign-ticket/
        ├── SPEC-TW-009-transition-ticket-status/
        ├── SPEC-TW-010-resolve-ticket/
        └── SPEC-TW-011-close-and-reopen-ticket/
```

代码仍属于：

```text
services/ticket-workflow-service/
```

## 23. Phase 03 之后

Phase 03 完成后，Ticket Workflow 已具备稳定的生命周期基础。下一阶段可以在不破坏核心状态机的前提下，引入 SLA、审批、自动化执行、通知或 Agent orchestration。

Phase 03 不提前实现这些能力，但必须通过稳定的事件契约和扩展点为它们提供可靠输入。
