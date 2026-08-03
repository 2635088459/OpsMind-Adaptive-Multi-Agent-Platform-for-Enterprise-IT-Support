# Phase 04 — Waiting for User Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：04
>
> Specs：`SPEC-TW-012` ～ `SPEC-TW-013`
>
> 前置条件：Phase 01、Phase 02、Phase 03 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 04 实现 Ticket 在处理过程中向 requester 请求补充信息，并在 requester 回复后恢复处理。

当前 Phase 03 的权威状态模型已经收敛为：

```text
IN_PROGRESS -> WAITING_FOR_USER -> IN_PROGRESS
```

早期 roadmap 中的 `TRIAGING / INVESTIGATING` 属于冻结前状态机；在当前实现中统一映射为 `IN_PROGRESS`。如果后续重新引入更细粒度 workflow runtime 状态，也不得改变 Ticket 持久化状态边界。

## 2. 业务价值

真实 IT 支持经常需要用户补充设备、环境、截图、错误时间、复现步骤或授权确认。没有 Phase 04 时，`WAITING_FOR_USER` 只是一个状态，缺少可以审计和恢复的请求-回复闭环。

Phase 04 提供：

- 一个 Ticket 同时最多一个 open user input request；
- 支持人员或 Automation Agent 可以明确提出问题；
- Ticket 进入 `WAITING_FOR_USER` 并记录等待元数据；
- requester 回复必须关联当前 open request；
- message 保存和状态恢复在同一事务完成；
- 旧 request、重复 reply 和乱序事件不会恢复错误 workflow；
- timeline、audit、outbox 和 idempotency 与 Phase 03 一致。

## 3. 范围

### 3.1 本 Phase 包含

- 创建 user input request；
- `IN_PROGRESS -> WAITING_FOR_USER`；
- 保存 requester-facing prompt、requestedBy、requestedAt、resumeStatus；
- 暂停或标记 SLA waiting 时间；
- requester 回复当前 open request；
- 原子写入 message 并执行 `WAITING_FOR_USER -> IN_PROGRESS`；
- 关闭 user input request；
- 清理 waiting metadata；
- status history、timeline、audit、outbox；
- 幂等、乐观锁、权限和错误模型；
- 契约测试、集成测试和 E2E。

### 3.2 本 Phase 不包含

- Approval；
- Tool execution；
- Notification delivery；
- 自动超时升级；
- SLA breach engine；
- 多轮表单工作流；
- requester 直接改状态；
- Agent runtime 的实际 resume 执行。

## 4. Phase 04 Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-012` | Request User Input | 创建补充信息请求并进入等待用户 |
| 2 | `SPEC-TW-013` | User Reply and Resume | 回复当前请求并恢复处理 |

## 5. 状态转换

| 当前状态 | 目标状态 | 触发操作 |
|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_USER` | Request User Input |
| `WAITING_FOR_USER` | `IN_PROGRESS` | User Reply and Resume |

未列出的转换默认非法。`WAITING_FOR_USER -> RESOLVED`、`WAITING_FOR_USER -> CLOSED` 和 `CLOSED -> WAITING_FOR_USER` 必须拒绝。

## 6. API 命令边界

```text
POST /api/v1/tickets/{ticketId}/user-input-requests
POST /api/v1/tickets/{ticketId}/user-input-requests/{requestId}/reply
```

所有写请求应支持：

```text
Authorization: Bearer <token>
Idempotency-Key: <uuid>
If-Match: "<ticket-version>"
X-Correlation-ID: <uuid>
```

## 7. 领域事件

```text
ticket.user-input-requested.v1
ticket.user-reply-received.v1
ticket.user-input-resumed.v1
```

`ticket.user-reply-received.v1` 表示 message 已保存；`ticket.user-input-resumed.v1` 表示有效当前请求已关闭且 Ticket 已恢复。

## 8. 数据模型

新增或确认：

```text
ticket_user_input_requests
ticket_messages
ticket_status_history
ticket_timeline
ticket_audit_log
outbox_events
idempotency_records
```

`ticket_user_input_requests` 至少包含：

- `request_id`
- `ticket_id`
- `request_status`
- `prompt`
- `requested_by_type`
- `requested_by_id`
- `requested_at`
- `resume_status`
- `answered_message_id`
- `answered_at`
- `expires_at`
- `correlation_id`

## 9. 事务与一致性

Request User Input 成功必须在一个事务中：

1. 验证 Ticket、状态、版本和权限；
2. 确认没有 open input request；
3. 创建 input request；
4. 更新 Ticket 为 `WAITING_FOR_USER`；
5. 写 status history、timeline、audit、outbox；
6. finalize idempotency response。

User Reply and Resume 成功必须在一个事务中：

1. 验证 Ticket、request、requester 和版本；
2. 保存 message；
3. 关闭 input request；
4. 更新 Ticket 为 `IN_PROGRESS`；
5. 清理 waiting metadata；
6. 写 status history、timeline、audit、outbox；
7. finalize idempotency response。

## 10. 退出条件

- `SPEC-TW-012` 与 `SPEC-TW-013` 文档和代码闭环；
- 一个 Ticket 不会出现两个 open user input request；
- requester reply 必须引用当前 open request 才能恢复；
- message 与状态恢复原子提交；
- duplicate reply 不会重复恢复；
- old request / stale workflow event 被拒绝或降级为普通 message；
- OpenAPI、AsyncAPI、migration 和测试全部通过。
