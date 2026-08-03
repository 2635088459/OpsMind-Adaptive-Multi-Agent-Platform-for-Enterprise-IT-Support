# SPEC-TW-008 — 领域规则

## 1. 命令

```text
AssignTicket(ticketId, assigneeId, reason, expectedVersion, idempotencyKey, actorContext)
ReassignTicket(ticketId, assigneeId, reason, expectedVersion, idempotencyKey, actorContext)
UnassignTicket(ticketId, reason, expectedVersion, idempotencyKey, actorContext)
```

Tenant、Actor、Roles 和 Queue Claims 必须来自可信认证中间件。

## 2. 聚合方法

```text
Ticket.assign(assignee, actor, reason, occurredAt)
Ticket.reassign(newAssignee, actor, reason, occurredAt)
Ticket.unassign(actor, reason, occurredAt)
```

Application Layer 在调用聚合方法前校验外部 Identity 与 Queue Membership；Aggregate 仍负责 Ticket 内部不变量。

## 3. 首次分配

Aggregate 必须：

1. 要求状态为 `TRIAGED`；
2. 要求 `assigneeId == null`；
3. 设置 `assigneeId`、`assignedAt` 与 `assignedBy`；
4. 将状态改为 `ASSIGNED`；
5. 版本只增加一次；
6. 产生负责人变更与状态变更事实。

## 4. 重新分配

Aggregate 必须：

1. 要求当前已有负责人；
2. 允许 `ASSIGNED`、`IN_PROGRESS`、`WAITING_FOR_USER`、`WAITING_FOR_APPROVAL`；
3. 要求新负责人不同于当前负责人；
4. 替换负责人和分配元数据；
5. 保持状态不变；
6. 版本只增加一次。

## 5. 取消分配

Aggregate 必须：

1. 要求状态为 `ASSIGNED`；
2. 要求当前已有负责人；
3. 清除负责人和分配元数据；
4. 将状态改为 `TRIAGED`；
5. 版本只增加一次。

## 6. Assignee 资格

执行命令时，Assignee 必须：

- 存在于相同 Tenant；
- 处于 active，且未 suspended/deleted；
- 具有可处理支持工作的角色；
- 是 Ticket `supportQueueId` 的 active 成员。

资格在命令的一致性边界内判断。之后的成员关系变化不能改写历史事实。

## 7. Actor 授权

- Requester 不得执行负责人命令；
- Support Agent 只能在 Policy 授予的 Operation 与 Queue 范围内操作；
- Support Lead 可在其管理的 Queue 中分配、重新分配和取消分配；
- Automation Agent 必须拥有明确的 Service Policy 与 Queue Scope；
- 跨 Tenant 访问按共享安全策略表现为 not found 或 denied。

## 8. 不变量

- `TRIAGED` 表示无负责人；
- `ASSIGNED`、`IN_PROGRESS` 与 waiting 状态必须有负责人；
- 一个命令不得静默转换为另一种操作；
- Queue 变更与负责人变更是不同命令；
- History 只能追加；
- 每次提交的 Aggregate Command 版本只增加一次。

## 9. 并发与幂等

Repository Update 必须包含 `WHERE id = ? AND tenant_id = ? AND version = ?`。更新行数为零时返回 `VERSION_CONFLICT`。

产生副作用前先检查幂等。完全相同的重放返回已存储的 Status、Body、Headers 与资源版本；相同 Key 但不同 Fingerprint 必须拒绝。

## 10. 领域事实

```text
TicketAssigned
TicketReassigned
TicketUnassigned
```

事实只能包含标识符与业务元数据，不得包含 Bearer Token、原始 Authorization Claims、私密消息或完整用户资料。

## 11. Application Handler 顺序

1. 认证并校验 Transport 数据；
2. 声明或检查幂等；
3. 按 Tenant 加载 Ticket；
4. 根据 Operation 与 Ticket Queue 授权 Actor；
5. 校验 Expected Version；
6. 在适用时解析并校验 Assignee；
7. 调用 Aggregate Method；
8. 在一个事务内持久化全部记录；
9. 保存可重放响应；
10. 返回响应与 ETag。
