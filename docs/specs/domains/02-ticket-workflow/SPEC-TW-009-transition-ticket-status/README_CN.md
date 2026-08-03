# SPEC-TW-009 — Transition Ticket Status（转换工单状态）

> 领域：Ticket Workflow
>
> 服务：`ticket-workflow-service`
>
> Phase：03 — 工单生命周期与负责人管理
>
> 状态：可以进入实现
>
> 前置条件：`SPEC-TW-001` ～ `SPEC-TW-008`

## 1. 目标

为获得授权的支持人员与自动化 Agent 提供受状态机约束的通用状态转换命令。

本 SPEC 只覆盖分配之后、解决之前的处理中与等待中状态推进：

```text
ASSIGNED -> IN_PROGRESS
IN_PROGRESS -> WAITING_FOR_USER
IN_PROGRESS -> WAITING_FOR_APPROVAL
WAITING_FOR_USER -> IN_PROGRESS
WAITING_FOR_APPROVAL -> IN_PROGRESS
```

分诊、分配、解决、关闭和重新打开继续由各自专用命令负责，不能通过本接口绕过业务规则。

## 2. 业务结果

系统必须能够回答：

- Ticket 是否已经开始处理；
- Ticket 当前是否等待请求人或审批；
- 谁在何时、因为什么原因改变了状态；
- waiting metadata 是否与当前状态一致；
- 同一状态转换请求是否已经处理过；
- 状态历史、时间线、审计和 outbox 是否与 Ticket 当前版本一致。

## 3. 范围内

- 引入 `IN_PROGRESS` 作为 Phase 03 的持久状态；
- 执行 `ASSIGNED -> IN_PROGRESS`；
- 执行 `IN_PROGRESS -> WAITING_FOR_USER`；
- 执行 `IN_PROGRESS -> WAITING_FOR_APPROVAL`；
- 执行 waiting 状态恢复到 `IN_PROGRESS`；
- 要求每次转换提供 `reason`；
- waiting for user 时保存 `waitingForRequesterSince`；
- waiting for approval 时保存 `approvalReference`；
- 恢复处理时清理 waiting metadata；
- 验证 Ticket 必须已有负责人才能进入处理中或等待中状态；
- RBAC 与 queue-level authorization；
- `If-Match` 乐观锁；
- `Idempotency-Key` 幂等控制；
- 写入 status history、Timeline、Audit、Idempotency 和 Transactional Outbox；
- 发布 `ticket.status-changed.v1`。

## 4. 范围外

- 分诊、分配、重新分配或取消分配；
- 保存 resolution summary 或 resolution code；
- 关闭、重新打开或创建新的 resolution cycle；
- 审批业务流程本身；
- SLA 暂停、恢复、违约升级或通知；
- Agent 工具执行、自动修复和独立 verification；
- 旧冻结状态机中的 `INVESTIGATING`、`EXECUTING`、`VERIFYING` 业务语义。

## 5. 状态规则

| 当前状态 | 目标状态 | 命令语义 |
|---|---|---|
| `ASSIGNED` | `IN_PROGRESS` | Start Work |
| `IN_PROGRESS` | `WAITING_FOR_USER` | Wait for User |
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | Wait for Approval |
| `WAITING_FOR_USER` | `IN_PROGRESS` | Resume Work |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Resume Work |

未列出的转换默认非法。

## 6. HTTP API

```text
POST /api/v1/tickets/{ticketId}/status-transitions
```

所有请求要求：

```http
If-Match: "<ticket-version>"
Idempotency-Key: <unique-key>
```

## 7. 事务边界

一个数据库事务必须写入：

1. Ticket status、waiting metadata、updated_at 和 version；
2. status history；
3. requester-safe timeline；
4. internal audit；
5. idempotency result；
6. outbox event。

任何写入失败都必须回滚整个命令。

## 8. 稳定错误码

`TICKET_NOT_FOUND`、`INVALID_STATUS_TRANSITION`、`TICKET_NOT_ASSIGNED`、`FORBIDDEN`、`QUEUE_ACCESS_DENIED`、`VERSION_CONFLICT`、`PRECONDITION_REQUIRED`、`IDEMPOTENCY_KEY_REUSED`、`REQUEST_IN_PROGRESS`、`VALIDATION_ERROR`。

## 9. 交付内容

- 中英文需求与实现文档；
- OpenAPI 与 AsyncAPI 契约；
- Flyway 参考迁移；
- 可执行 HTTP 示例；
- Domain、Application、Persistence、Contract、Security、Concurrency 与 E2E 测试。

## 10. 完成定义

- 所有验收标准通过；
- 状态转换规则由单一来源维护；
- 非法转换不会产生任何写入；
- waiting metadata 与当前状态一致；
- 重试返回原始结果且不产生重复副作用；
- 过期并发更新被拒绝；
- 每次成功状态变更均可追踪；
- API 与事件 payload 均通过契约校验。
