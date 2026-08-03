# SPEC-TW-010 — 领域规则

## 1. 命令

```text
ResolveTicket(ticketId, resolutionCode, resolutionSummary, expectedVersion, idempotencyKey, actorContext)
```

Tenant、Actor、Roles、Scopes 和 Queue Claims 必须来自可信认证中间件。

## 2. 聚合方法

```text
Ticket.resolve(currentStatus, currentAssigneeId, currentResolutionCycleId, resolutionCode, resolutionSummary, actor, occurredAt)
```

Application Layer 负责加载 guard、校验 actor 授权、校验版本、校验 resolution cycle 存在且未完成。Aggregate 负责 Ticket 内部状态机不变量。

## 3. 状态转换

| 当前状态 | 目标状态 | transitionId | reasonCode |
|---|---|---|---|
| `IN_PROGRESS` | `RESOLVED` | `SM-010` | `TICKET_RESOLVED` |

未列出的转换必须拒绝。`RESOLVED -> CLOSED` 属于 `SPEC-TW-011`。

## 4. Resolve 规则

Aggregate 必须：

1. 要求当前状态为 `IN_PROGRESS`；
2. 要求当前已有负责人；
3. 要求当前存在 resolution cycle；
4. 要求 `resolutionCode` 为受控枚举；
5. 要求 `resolutionSummary` trim 后非空且满足长度限制；
6. 将状态设置为 `RESOLVED`；
7. 设置 `resolvedBy`、`resolvedAt`、`resolutionCode` 和 `resolutionSummary`；
8. 清理 waiting metadata；
9. 保留 current assignee；
10. 版本只增加一次；
11. 产生 `TicketResolved` 领域事实。

## 5. Resolution Code

```text
FIXED
WORKAROUND_PROVIDED
DUPLICATE
REQUEST_FULFILLED
NOT_REPRODUCIBLE
USER_ERROR
NO_ACTION_REQUIRED
```

## 6. Resolution Cycle

当前 resolution cycle 必须：

- 属于同一个 Ticket；
- 状态为 active/in-progress；
- 尚未完成；
- 在同一事务中写入完成时间、完成 actor、resolution code 和 summary snapshot。

历史 cycle 不得被覆盖。

## 7. Actor 授权

- Requester 不得解决工单；
- Support Agent 只能解决授权队列内的 Ticket；
- Support Lead 可解决其管理队列内的 Ticket；
- Automation Agent 必须拥有明确的 service identity scope；
- 本 SPEC 推荐 scope：`ticket:resolve`。

## 8. 不变量

- `RESOLVED` Ticket 必须有 `resolved_at`、`resolved_by`、`resolution_code` 和 `resolution_summary`；
- `RESOLVED` Ticket 保留当前负责人；
- `RESOLVED` Ticket 不保留 waiting metadata；
- 解决不能修改分类、队列或负责人；
- status history 和 resolution cycle history 只能追加或完成当前 cycle；
- 每次成功命令版本只增加一次。

## 9. 并发与幂等

Repository update 必须包含 `WHERE ticket_id = ? AND version = ? AND status = 'IN_PROGRESS' AND current_support_user_id IS NOT NULL`。

产生副作用前先检查幂等。完全相同的重放返回已存储的 status、body、headers 与资源版本；相同 key 但不同 fingerprint 必须拒绝。

## 10. 领域事实

```text
TicketResolved
```

事实只能包含标识符、状态、负责人、resolution code、summary、actor、版本和时间戳。不得包含 token、原始 claims、私密消息或完整用户资料。

## 11. Application Handler 顺序

1. 认证并校验 transport 数据；
2. 声明或检查幂等；
3. 加载 Ticket resolve guard；
4. 校验 actor command scope；
5. 校验 queue-level authorization；
6. 校验 expected version；
7. 校验 resolution cycle；
8. 调用 Aggregate Method；
9. 在一个事务内持久化 Ticket、resolution cycle、history、timeline、audit、outbox；
10. 保存可重放响应；
11. 返回响应与 ETag。
