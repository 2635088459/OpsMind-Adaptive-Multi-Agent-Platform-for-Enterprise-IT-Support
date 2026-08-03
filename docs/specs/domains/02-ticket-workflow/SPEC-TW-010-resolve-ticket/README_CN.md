# SPEC-TW-010 — Resolve Ticket（解决工单）

> 领域：Ticket Workflow
>
> 服务：`ticket-workflow-service`
>
> Phase：03 — 工单生命周期与负责人管理
>
> 状态：可以进入实现
>
> 前置条件：`SPEC-TW-001` ～ `SPEC-TW-009`

## 1. 目标

将“解决工单”建模为带结构化业务数据的专用命令，而不是普通状态转换。

本 SPEC 只覆盖：

```text
IN_PROGRESS -> RESOLVED
```

关闭和重新打开属于 `SPEC-TW-011`。普通状态转换仍属于 `SPEC-TW-009`。

## 2. 业务结果

系统必须能够回答：

- Ticket 是由谁在何时解决的；
- 解决原因和解决摘要是什么；
- 当前 resolution cycle 是否已完成；
- 解决时的负责人是谁；
- 同一解决请求是否已经处理过；
- Ticket、resolution cycle、status history、timeline、audit 和 outbox 是否一致。

## 3. 范围内

- 仅允许 `IN_PROGRESS -> RESOLVED`；
- 要求 `resolutionSummary` 非空；
- 要求 `resolutionCode` 为受控枚举；
- 保存 `resolvedBy` 和 `resolvedAt`；
- 清理 waiting metadata；
- 保留当前负责人；
- 完成当前 resolution cycle；
- 写入 status history、timeline、audit、idempotency 和 outbox；
- 发布 `ticket.resolved.v1`；
- 支持 `If-Match` 乐观锁；
- 支持 `Idempotency-Key` 幂等控制；
- RBAC 与 queue-level authorization。

## 4. 范围外

- 关闭工单；
- 重新打开工单；
- auto-close scheduler；
- requester 确认满意度；
- SLA breach 计算；
- 通知；
- 审批工作流；
- Agent 工具执行与 verification；
- 修改负责人或支持队列。

## 5. Resolution Codes

```text
FIXED
WORKAROUND_PROVIDED
DUPLICATE
REQUEST_FULFILLED
NOT_REPRODUCIBLE
USER_ERROR
NO_ACTION_REQUIRED
```

## 6. HTTP API

```text
POST /api/v1/tickets/{ticketId}/resolution
```

所有请求要求：

```http
If-Match: "<ticket-version>"
Idempotency-Key: <unique-key>
```

## 7. 事务边界

一个数据库事务必须写入：

1. Ticket status、resolution fields、waiting metadata、updated_at 和 version；
2. 当前 resolution cycle 的完成信息；
3. status history；
4. requester-safe timeline；
5. internal audit；
6. idempotency result；
7. outbox event。

任何写入失败都必须回滚整个命令。

## 8. 稳定错误码

`TICKET_NOT_FOUND`、`INVALID_STATUS_TRANSITION`、`TICKET_NOT_ASSIGNED`、`RESOLUTION_CODE_INVALID`、`RESOLUTION_CYCLE_NOT_FOUND`、`RESOLUTION_CYCLE_ALREADY_COMPLETED`、`FORBIDDEN`、`QUEUE_ACCESS_DENIED`、`VERSION_CONFLICT`、`PRECONDITION_REQUIRED`、`IDEMPOTENCY_KEY_REUSED`、`REQUEST_IN_PROGRESS`、`VALIDATION_ERROR`。

## 9. 交付内容

- 中英文需求与实现文档；
- OpenAPI 与 AsyncAPI 契约；
- Flyway 参考迁移；
- 可执行 HTTP 示例；
- Domain、Application、Persistence、Contract、Security、Concurrency 与 E2E 测试。

## 10. 完成定义

- 所有验收标准通过；
- 非 `IN_PROGRESS` 工单无法被解决；
- resolution summary/code 被持久化并可追踪；
- 当前 resolution cycle 被正确完成；
- 重试返回原始结果且不产生重复副作用；
- 过期并发更新被拒绝；
- API 与事件 payload 均通过契约校验。
