# SPEC-TW-009 — 领域规则

## 1. 命令

```text
TransitionTicketStatus(ticketId, targetStatus, reason, waitingForRequesterSince, approvalReference, expectedVersion, idempotencyKey, actorContext)
```

Tenant、Actor、Roles、Scopes 和 Queue Claims 必须来自可信认证中间件。

## 2. 聚合方法

```text
Ticket.transitionStatus(currentStatus, currentAssigneeId, targetStatus, reason, waitingMetadata, actor, occurredAt)
```

Application Layer 在调用聚合方法前完成 I/O 相关校验，包括 Ticket guard 加载、actor 授权和版本检查。Aggregate 负责 Ticket 内部状态机不变量。

## 3. 单一状态转换矩阵

| 当前状态 | 目标状态 | transitionId | reasonCode |
|---|---|---|---|
| `ASSIGNED` | `IN_PROGRESS` | `SM-005` | `WORK_STARTED` |
| `IN_PROGRESS` | `WAITING_FOR_USER` | `SM-006` | `WAITING_FOR_USER` |
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | `SM-007` | `WAITING_FOR_APPROVAL` |
| `WAITING_FOR_USER` | `IN_PROGRESS` | `SM-008` | `WORK_RESUMED` |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-009` | `WORK_RESUMED` |

未列出的转换必须拒绝。`RESOLVED`、`CLOSED` 和 reopen 相关转换属于 `SPEC-TW-010/011`。

## 4. Start Work

Aggregate 必须：

1. 要求当前状态为 `ASSIGNED`；
2. 要求当前已有负责人；
3. 将状态设置为 `IN_PROGRESS`；
4. 清理 waiting metadata；
5. 版本只增加一次；
6. 产生 `TicketStatusChanged` 领域事实。

## 5. Wait for User

Aggregate 必须：

1. 要求当前状态为 `IN_PROGRESS`；
2. 要求当前已有负责人；
3. 将状态设置为 `WAITING_FOR_USER`；
4. 保存 `waitingForRequesterSince`，未提供时使用命令发生时间；
5. 清理 `approvalReference`；
6. 版本只增加一次。

## 6. Wait for Approval

Aggregate 必须：

1. 要求当前状态为 `IN_PROGRESS`；
2. 要求当前已有负责人；
3. 要求非空 `approvalReference`；
4. 将状态设置为 `WAITING_FOR_APPROVAL`；
5. 清理 `waitingForRequesterSince`；
6. 版本只增加一次。

## 7. Resume Work

Aggregate 必须：

1. 要求当前状态为 `WAITING_FOR_USER` 或 `WAITING_FOR_APPROVAL`；
2. 要求当前已有负责人；
3. 将状态设置为 `IN_PROGRESS`；
4. 清理全部 waiting metadata；
5. 版本只增加一次。

## 8. Actor 授权

- Requester 不得执行状态转换命令；
- Support Agent 只能在 Policy 授予的 Operation 与 Queue 范围内推进状态；
- Support Lead 可在其管理的 Queue 内推进状态；
- Automation Agent 必须拥有明确的 service identity scope；
- 本 SPEC 推荐统一 scope：`ticket:transition`。

## 9. 不变量

- `IN_PROGRESS`、`WAITING_FOR_USER`、`WAITING_FOR_APPROVAL` 必须有负责人；
- waiting 状态必须携带与状态匹配的 metadata；
- 非 waiting 状态不得保留 active waiting metadata；
- 一个通用状态命令不得执行分诊、分配、解决、关闭或重新打开；
- status history 只能追加；
- 每次成功提交的 Aggregate Command 版本只增加一次。

## 10. 并发与幂等

Repository update 必须包含 `WHERE ticket_id = ? AND version = ? AND status IN (?)`。更新行数为零时按 guard 结果区分 `VERSION_CONFLICT` 或 `INVALID_STATUS_TRANSITION`。

产生副作用前先检查幂等。完全相同的重放返回已存储的 status、body、headers 与资源版本；相同 key 但不同 fingerprint 必须拒绝。

## 11. 领域事实

```text
TicketStatusChanged
```

事实只能包含标识符、状态、waiting metadata、actor、reason 和版本信息，不得包含 Bearer Token、原始 Authorization Claims、私密消息或完整用户资料。

## 12. Application Handler 顺序

1. 认证并校验 transport 数据；
2. 声明或检查幂等；
3. 加载 Ticket transition guard；
4. 校验 Actor command scope；
5. 校验 queue-level authorization；
6. 校验 expected version；
7. 调用 Aggregate Method；
8. 在一个事务内持久化 Ticket、history、timeline、audit、outbox；
9. 保存可重放响应；
10. 返回响应与 ETag。
