# SPEC-TW-007 — 领域规则

## 1. 聚合命令

```text
TriageTicketCommand
  ticketId
  categoryId
  subcategoryId?
  priority
  supportQueueId
  reason
  expectedVersion
  idempotencyKey
  actorContext
  correlationId
```

Transport Layer 负责构造该命令。`actorContext`、Tenant Identity 和授权 Claims 必须来自可信的认证中间件。

## 2. 聚合方法

```text
Ticket.triage(classification, routing, actor, occurredAt)
```

聚合方法必须：

1. 要求当前状态为 `OPEN`；
2. 要求标识符与优先级已合法标准化；
3. 设置 `categoryId`、`subcategoryId`、`priority` 和 `supportQueueId`；
4. 设置 `triagedBy` 与 `triagedAt`；
5. 将状态改为 `TRIAGED`；
6. 聚合版本只增加一次；
7. 生成供状态历史、Timeline、Audit 和 Outbox 使用的领域结果。

Category 是否存在、Queue 权限等属于 Application/Domain Service 的检查，应在聚合变更前完成。这些检查仍应处于命令事务内，或使用不会把已停用记录错误识别为有效记录的一致性模型。

## 3. 状态规则

```text
OPEN --TriageTicket--> TRIAGED
```

不接受其他来源状态。重新分诊、修改分类、修改优先级和队列转移属于未来独立命令，不能通过本 Endpoint 绕过边界。

## 4. 分类规则

- Category 必填且 Active；
- Subcategory 可选；
- 提供 Subcategory 时，它必须 Active 且 `parent_category_id = categoryId`；
- 持久化 Identifier，显示名称由 Query Projection 解析；
- 以后停用 Catalog Item 时，不能重写历史分诊数据。

## 5. 优先级规则

可接受值：

```text
LOW
MEDIUM
HIGH
CRITICAL
```

API 不自动推断优先级。`CRITICAL` 可以要求额外权限，例如 `ticket:triage:critical`；如果项目尚未引入该权限，本 SPEC 可使用普通队列分诊权限，但必须在配置中明确这个决定。

## 6. 队列规则

- Queue 必须属于同一 Tenant 且 Active；
- Actor 必须拥有 `ticket:triage` 和目标 Queue Access；
- Requester 不能分诊；
- Support Agent 只能分诊到被授权队列；
- Support Lead 可以跨其授权范围内的队列分诊；
- Automation Agent 使用 Service Identity，且必须获得明确 Queue Grant；
- 分诊后 Ticket 仍没有 Assignee；分配属于 `SPEC-TW-008`。

## 7. 操作者与时间规则

- `triagedBy` 来自已认证 Principal；
- `triagedAt` 来自服务端 UTC Clock；
- Application 必须捕获同一个 `occurredAt`，供 Ticket、History、Timeline、Audit 和 Event 共同使用；
- Requester 不能通过 Body 或 Header 冒充分诊人。

## 8. 并发

`If-Match` 必填。Repository Update 必须同时包含 Ticket/Tenant 和预期版本：

```sql
UPDATE tickets
SET ..., version = version + 1
WHERE id = :ticketId
  AND tenant_id = :tenantId
  AND version = :expectedVersion
  AND status = 'OPEN';
```

影响行数为 0 时，应在不泄露 Tenant 数据的前提下判定：

1. Ticket 不存在或不可访问 → `TICKET_NOT_FOUND`；
2. 版本不匹配 → `VERSION_CONFLICT`；
3. 版本相同但状态不是 `OPEN` → `INVALID_TICKET_STATE`。

针对同一版本的两个并发命令只能有一个成功。

## 9. 幂等

唯一范围：

```text
tenantId + actorId + commandName + idempotencyKey
```

标准化请求哈希应包含 `ticketId`、全部标准化 Body 字段和预期版本；不包含 Authorization Token 与 Correlation ID。

- 新 Key → 执行并保存响应；
- 相同 Key + 相同 Hash → 返回已保存响应；
- 相同 Key + 不同 Hash → `IDEMPOTENCY_KEY_REUSED`；
- Validation/Authorization 失败时不占用 Key；
- 一旦开始变更，幂等完成记录必须与业务修改处于同一事务。

## 10. 事务边界

一个事务执行：

1. 获取/验证幂等记录；
2. 在 Tenant 范围加载 Ticket；
3. 验证版本和状态；
4. 验证 Category、Subcategory、Queue 和权限；
5. 修改聚合；
6. 更新 Ticket；
7. 插入状态历史；
8. 插入 Timeline；
9. 插入 Audit；
10. 插入 Outbox Event；
11. 保存幂等响应；
12. Commit。

事务内不能直接调用 Message Broker。独立 Outbox Publisher 负责发送已经提交的事件。

## 11. 失败不变量

Commit 前的任何异常都必须回滚全部写入。重试不能产生重复领域事实。

