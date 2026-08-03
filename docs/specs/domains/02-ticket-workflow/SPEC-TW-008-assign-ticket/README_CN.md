# SPEC-TW-008 — Assign Ticket（分配工单）

> 领域：Ticket Workflow  
> 服务：`ticket-workflow-service`  
> Phase：03 — 工单生命周期与负责人管理  
> 状态：可以进入实现  
> 前置条件：`SPEC-TW-001` ～ `SPEC-TW-007`

## 1. 目标

为获得授权的支持人员与自动化 Agent 提供安全的工单分配、重新分配和取消分配命令。

这是一个 command-side vertical slice。命令成功时，Ticket Aggregate、负责人历史、必要的状态历史、Timeline、Audit、幂等记录和 Transactional Outbox 必须在同一个事务中写入。

## 2. 业务结果

系统必须随时能够回答：

- 当前由谁负责 Ticket；
- 谁在何时、因为什么原因改变了负责人；
- 被分配人是否具备目标支持队列的处理资格；
- 本次操作是首次分配、重新分配还是取消分配；
- 相同命令是否已经处理过。

## 3. 范围内

- 将 `TRIAGED` Ticket 首次分配给负责人；
- 在不改变当前工作状态的前提下重新分配；
- 将 `ASSIGNED` Ticket 取消分配并退回 `TRIAGED`；
- 校验用户有效性、Tenant、支持角色与队列成员资格；
- Actor 的 RBAC 与队列级授权；
- 使用 `If-Match` 实现乐观锁；
- 使用 `Idempotency-Key` 防止重复副作用；
- 写入负责人历史、Timeline、Audit 与 Outbox；
- 发布 `ticket.assigned.v1`、`ticket.reassigned.v1`、`ticket.unassigned.v1`。

## 4. 范围外

- 自动路由或负载均衡；
- 排班、容量、在线状态或技能评分；
- 开始处理工单（属于 `SPEC-TW-009`）；
- 解决、关闭或重新打开（属于 `SPEC-TW-010/011`）；
- 通知、SLA 升级和审批流程；
- 修改支持队列；队列变更应通过重新分诊完成。

## 5. 状态规则

| 命令 | 要求状态 | 结果状态 |
|---|---|---|
| Assign | `TRIAGED` 且无负责人 | `ASSIGNED` |
| Reassign | `ASSIGNED`、`IN_PROGRESS`、`WAITING_FOR_USER` 或 `WAITING_FOR_APPROVAL` | 状态不变 |
| Unassign | `ASSIGNED` | `TRIAGED` |

禁止直接取消分配处于 `IN_PROGRESS` 或 waiting 状态的 Ticket。必须先通过 `SPEC-TW-009` 将其恢复到可分配的工作流状态。

## 6. HTTP API

```text
POST /api/v1/tickets/{ticketId}/assign
POST /api/v1/tickets/{ticketId}/reassign
POST /api/v1/tickets/{ticketId}/unassign
```

所有命令都要求：

```http
If-Match: "<ticket-version>"
Idempotency-Key: <unique-key>
```

## 7. 事务边界

一个数据库事务必须写入：

1. Ticket 负责人字段与版本；
2. 负责人历史；
3. 状态发生变化时的状态历史；
4. Requester-safe Timeline；
5. 内部 Audit；
6. 幂等结果；
7. Outbox Event。

任何写入失败都必须回滚整个命令。

## 8. 稳定错误码

`TICKET_NOT_FOUND`、`INVALID_TICKET_STATE`、`TICKET_ALREADY_ASSIGNED`、`TICKET_NOT_ASSIGNED`、`ASSIGNEE_NOT_FOUND`、`ASSIGNEE_INACTIVE`、`ASSIGNEE_NOT_SUPPORT_AGENT`、`ASSIGNEE_NOT_IN_QUEUE`、`FORBIDDEN`、`QUEUE_ACCESS_DENIED`、`VERSION_CONFLICT`、`IDEMPOTENCY_KEY_REUSED`、`VALIDATION_ERROR`。

## 9. 交付内容

- 中英文需求与实现文档；
- OpenAPI 与 AsyncAPI 契约；
- Flyway 参考迁移；
- 可执行 HTTP 示例；
- Domain、Application、Persistence、Contract、Security、Concurrency 与 E2E 测试。

## 10. 完成定义

- 所有验收标准通过；
- 未授权或不符合资格的分配不会产生任何写入；
- 重试返回原始结果且不产生重复副作用；
- 过期并发更新被拒绝；
- 每次成功的负责人变更均可追踪；
- API 与事件 Payload 均通过所提供的契约校验。
