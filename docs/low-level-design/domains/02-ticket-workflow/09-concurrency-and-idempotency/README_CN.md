# OpsMind Ticket Workflow — 09 Concurrency and Idempotency

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Concurrency and Idempotency Design  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `02-business-invariants/README_CN.md`、`03-state-machine/README_CN.md`、`04-use-cases/README_CN.md`、`05-api-contracts/README_CN.md`、`06-event-contracts/README_CN.md`、`07-data-model/README_CN.md`、`08-transaction-and-outbox/README_CN.md`  
> **并发模型：** Optimistic Concurrency + Database Constraints + Idempotent Commands + Idempotent Consumers  
> **建议路径：** `docs/low-level-design/domains/02-ticket-workflow/09-concurrency-and-idempotency/README_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow 在多用户、多服务、多线程、多实例和 RabbitMQ At-least-once Delivery 环境下，如何保证同一个 Ticket 只能产生合法、可解释、可恢复的业务结果。

本文档冻结：

- HTTP Command Idempotency
- Internal Command Idempotency
- Event Consumer Deduplication
- Canonical Request / Payload Hash
- Optimistic Lock
- Aggregate Version
- Event Sequence
- Duplicate、Stale、Out-of-order 判定
- Multi-instance 并发
- Queue Consumer 并发
- Scheduler 并发
- Race Condition 决策表
- Retry 与 Re-evaluation
- Recovery 与 Reconciliation
- Database Constraint 的最终防线
- Metrics、Alert 和测试要求

核心目标：

```text
同一业务意图重复提交，最多产生一次业务效果。
不同业务意图同时竞争，只允许一个符合当前状态机的结果提交。
迟到事件、旧 Workflow 事件和重复事件不能污染当前处理周期。
任何重试都必须重新读取状态并重新验证 Business Invariant。
```

---

# 2. 并发来源

Ticket Workflow 必须处理以下并发来源：

## 2.1 用户端重复请求

例如：

- 用户双击 Create Ticket。
- 浏览器超时后重新提交。
- 手机网络重连后重放请求。
- 前端重复发送 Cancel 或 Reopen。

## 2.2 多 Support 用户同时操作

例如：

- 两名 Support 同时 Assign。
- 一名 Support Cancel，另一名 Support Escalate。
- Support Close 与 Requester Reopen 同时发生。

## 2.3 多服务事件竞争

例如：

- `approval.granted` 与 `ticket.cancelled` 竞争。
- `tool.execution.completed` 与 `tool.execution.result_unknown` 竞争。
- `verification.completed` 与 Reopen 竞争。
- `agent.workflow.failed` 与 Verification Success 竞争。

## 2.4 RabbitMQ 重复和乱序

来源：

- Producer 重复发布。
- Consumer Commit 后 ACK 前崩溃。
- Retry Queue 导致事件晚到。
- 不同 Producer 和 Queue 的相对顺序不同。

## 2.5 Scheduler 并发

例如：

- 多个 Auto-close 实例扫描同一 Ticket。
- Auto-close 与 Reopen 同时执行。
- SLA Breach Job 与 Resolve 同时执行。
- Cleanup Job 多实例同时运行。

## 2.6 Outbox Publisher 多实例

多个 Publisher 同时 Claim 和 Publish Outbox Row。

---

# 3. 并发一致性目标

## 3.1 Single Aggregate Serializability by Effect

系统不承诺所有请求按到达顺序执行，但承诺：

> 对同一个 Ticket，最终提交结果等价于某一个符合状态机和业务不变量的串行执行顺序。

实现方式：

```text
Expected Version
+
Atomic Transaction
+
Unique / Partial Unique Constraint
+
Domain Guard Re-evaluation
```

## 3.2 Effectively-once Business Effect

RabbitMQ 仍然可能重复发送 Event，但业务效果最多应用一次。

```text
At-least-once Delivery
+
Processed Event Store
+
Stable Business IDs
+
Optimistic Lock
```

## 3.3 No Lost Update

任何修改当前 Ticket Snapshot 的操作必须基于：

```text
expectedVersion
```

不允许 Last-write-wins。

## 3.4 No Cross-cycle Contamination

旧 Resolution Cycle、Workflow、Action、Execution Attempt 或 Verification Attempt 的事件，不得改变新 Cycle。

---

# 4. 并发身份层级

系统通过多层 Identity 判断一个请求或事件是否与当前业务意图相同。

| 层级 | ID | 用途 |
|---|---|---|
| HTTP Request | `idempotencyKey` | 去重用户或客户端 Command |
| Command | `commandId` | Trace、Audit、内部去重 |
| Event | `eventId` | Consumer Deduplication |
| Ticket Aggregate | `ticketId` | 并发作用域 |
| Aggregate Snapshot | `version` | Lost Update 防护 |
| Resolution Cycle | `resolutionCycleId` | 区分 Reopen 前后周期 |
| Agent Workflow | `workflowId` | 区分当前 Agent 执行图 |
| Pending Action | `actionId` | 区分业务操作 |
| Approval | `approvalId` | 区分审批决策 |
| Tool Execution | `toolExecutionId` | 区分执行实例 |
| Tool Attempt | `executionAttemptId` | 区分重试 |
| Resolution Attempt | `resolutionAttemptId` | 区分解决方案尝试 |
| Verification | `verificationId` | 区分验证任务 |
| Verification Attempt | `attemptNumber` | 控制失败次数 |
| Scheduler Job | `jobKey` | 去重 Auto-close 等任务 |

仅靠 `ticketId` 不足以判断事件是否有效。

---

# 5. Command Idempotency 分类

## 5.1 必须幂等的 Public Commands

```text
Create Ticket
Add Ticket Message
Cancel Ticket
Reopen Ticket
Confirm Resolution
```

## 5.2 必须幂等的 Support Commands

```text
Request User Input
Assign Ticket
Escalate Ticket
Retry Automation
Support Close
```

## 5.3 必须幂等的 Internal Commands

```text
Start Triage
Complete Classification
Associate Workflow
Start Verification
```

即使 Internal Command 最终由 Event 触发，也必须具有稳定 `commandId` 或 `eventId`。

---

# 6. Idempotency-Key 作用域

唯一约束：

```text
actor_scope + idempotency_key
```

推荐 `actor_scope`：

```text
user:{subject}:{operationFamily}
support:{subject}:{operationFamily}
service:{clientId}:{operationFamily}
scheduler:{jobType}
```

示例：

```text
user:user-123:createTicket
user:user-123:cancelTicket
support:support-42:assignTicket
service:agent-runtime:startTriage
scheduler:auto-close
```

同一个 Key 可以在不同 Operation Family 使用，但为了减少误用，推荐客户端为每次业务意图生成全局唯一 Key。

---

# 7. Canonical Request Hash

## 7.1 目的

检测：

```text
相同 Idempotency-Key
+
不同业务 Payload
```

## 7.2 Canonicalization

参与 Hash：

```text
HTTP method
normalized route template
actor scope
canonical JSON body
selected semantic headers
```

不参与：

```text
JWT
traceparent
X-Correlation-Id
请求时间
Header 顺序
JSON Field 顺序
无业务语义的空白
```

## 7.3 Canonical JSON 规则

- Object Key 按字典序排序。
- UTF-8 编码。
- 数字使用稳定表示。
- 区分 `null` 与字段缺失。
- Array 保持原顺序，除非业务定义为 Set。
- 字符串不自动改变大小写，除非字段规范明确要求。
- 时间先标准化为 UTC ISO 8601。
- Optional 默认值在 Hash 前由 DTO Normalizer 补齐。

## 7.4 Hash

```text
SHA-256(canonical request)
```

只保存 Hash，不保存原始 Idempotency-Key 到日志。

---

# 8. HTTP Command Idempotency Algorithm

```text
1. Authenticate actor
2. Build actorScope
3. Normalize request
4. Compute requestHash
5. Begin transaction
6. Try to insert IN_PROGRESS idempotency record
7. If inserted:
       execute business use case
       save response
       mark COMPLETED
       commit
       return response
8. If existing:
       compare requestHash
       if different:
           return IDEMPOTENCY_KEY_REUSED
       if COMPLETED:
           return stored response
       if IN_PROGRESS and fresh:
           return REQUEST_IN_PROGRESS
       if IN_PROGRESS and stale:
           run reconciliation
       if FAILED_RETRYABLE:
           reserve recovery and retry
       if FAILED_FINAL:
           return stored final error
```

---

# 9. Idempotent Response 语义

重复请求必须尽量返回第一次已提交结果：

```text
相同 HTTP Status
相同 resourceId
相同主要 Response Body
```

允许变化：

```text
新的 traceId
新的 response timestamp metadata
```

不允许重复生成：

- 新 Ticket
- 新 Message
- 新 Workflow
- 新 Resolution Cycle
- 新 Outbox Event
- 新 Tool Execution

对于 Replay，可以返回：

```http
Idempotency-Replayed: true
```

---

# 10. `IN_PROGRESS` 记录并发

## 10.1 两个请求同时使用同一 Key

数据库 Unique Constraint 只允许一个成功 Reserve。

另一个请求读取已有记录。

## 10.2 推荐响应

如果第一个请求仍在执行：

```http
409 Conflict
Retry-After: 1
```

```text
REQUEST_IN_PROGRESS
```

MVP 不让第二个请求长时间阻塞等待第一个事务。

## 10.3 Stale Threshold

```text
5 minutes
```

超过后不能直接认为失败，必须执行 Reconciliation。

---

# 11. Idempotency Reconciliation

对 Stale `IN_PROGRESS`：

1. 根据 `operation_id` 和 `resource_id` 查询业务记录。
2. 查询相关 History。
3. 查询关联 Outbox Event。
4. 判断业务事务是否已成功提交。
5. 如果已提交：
   - 重建安全 Response。
   - 标记 `COMPLETED`。
6. 如果未提交：
   - 标记 `FAILED_RETRYABLE`。
   - 允许同 Key 重新执行。
7. 如果状态无法判断：
   - 保持 Block。
   - Alert。
   - 不创建第二份资源。

---

# 12. Event Deduplication Algorithm

```text
1. Validate Event Envelope and Payload
2. Compute payloadHash
3. Begin transaction
4. Lookup (consumerName, eventId)
5. If record exists:
       if payloadHash differs:
           rollback
           DLQ
           security alert
       else:
           commit/no-op
           ACK duplicate
6. If no record:
       load Ticket
       classify event
       apply or record stale/rejected
       insert Processed Event
       commit
       ACK
```

主键：

```text
consumer_name + event_id
```

---

# 13. Duplicate Event 定义

Event 被判定为 Duplicate，当且仅当：

```text
相同 consumerName
相同 eventId
相同 payloadHash
```

处理：

```text
不重复更新 Ticket
不重复写 Status History
不重复写 Outbox
ACK
增加 Duplicate Metric
```

---

# 14. Event ID 重用

如果：

```text
相同 consumerName
相同 eventId
不同 payloadHash
```

则不是普通 Duplicate。

处理：

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
Immediate DLQ
Security Alert
Producer Contract Violation
```

不得尝试猜测哪个 Payload 正确。

---

# 15. Business Duplicate

即使 EventId 不同，业务上仍可能是重复事实。

示例：

```text
两个不同 eventId
都表示同一个 approvalId 已 Granted
```

系统还必须使用稳定 Business IDs 防止重复效果：

```text
approvalId
actionId
toolExecutionId
verificationId
workflowId
resolutionAttemptId
```

例如：

```text
Pending Action 已由 approvalId=apr-900 授权
新的 EventId 再次表示 apr-900 Granted
→ Idempotent business success
```

---

# 16. Aggregate Version

## 16.1 Ticket Version

`ticket.tickets.version`：

- 创建时为 `0`。
- 每次 Ticket Aggregate 核心变化增加 `1`。
- 与 Status History 和 Outbox Aggregate Version 对齐。

## 16.2 哪些修改递增 Ticket Version

包括：

- Status
- Category / Subcategory
- Priority
- Assignment
- Active Workflow
- Current Resolution Cycle
- Pending Action Snapshot 相关状态
- Resolve / Close / Cancel / Reopen
- Open User Request 引用

普通只读查询不增加。

独立 Message 创建是否增加 Ticket Version：

- 如果只是普通 Message，不改变 Ticket 状态：MVP 可以不增加 Ticket Version。
- 如果 Message 恢复 `WAITING_FOR_USER`：必须增加 Ticket Version。

## 16.3 SLA Version

`ticket_sla_cycles.version` 独立于 Ticket Version。

如果同一事务同时改变 Ticket 与 SLA：

```text
两者各自按自身 Aggregate 递增
```

---

# 17. Expected Version 来源

## API

```http
If-Match: "<ticketVersion>"
```

## Internal Command

```text
expectedVersion
```

## Event Consumer

不信任 Producer 提供的 Ticket Current Version 作为更新依据。

Consumer：

1. 加载当前 Ticket。
2. 使用加载到的 Version 进行更新。
3. 使用 Event Reference 判断 Stale / Current。
4. Event Envelope 的 `aggregateVersion` 仅用于 Ordering 和诊断。

---

# 18. Optimistic Lock Algorithm

```text
attempt = 0

while attempt < 3:
    load Ticket
    classify whether command/event is already applied
    validate state and references
    compute transition
    update WHERE version = loadedVersion

    if one row updated:
        write history/outbox
        commit
        success

    rollback
    attempt += 1
    short jitter backoff

reload Ticket
re-evaluate

if already applied:
    return idempotent success

if no longer legal:
    return stale / invalid state / conflict

otherwise:
    return CONCURRENT_UPDATE or retry queue
```

关键规则：

```text
Retry 前必须 Reload。
Retry 后必须重新执行 Guard。
不能复用第一次计算得到的目标状态或 Domain Event。
```

---

# 19. Duplicate、Stale、Out-of-order 判定顺序

Consumer 必须按以下顺序判定：

```text
1. Envelope / Schema Valid?
2. EventId Duplicate?
3. EventId Payload Conflict?
4. Ticket Exists?
5. Event belongs to this Ticket?
6. Resolution Cycle matches?
7. Workflow matches?
8. Action / Approval / Execution / Verification matches?
9. Business effect already applied?
10. Source state currently legal?
11. Required predecessor may be missing?
12. Apply / ACK stale / Retry out-of-order / DLQ corruption
```

先检查 Identity，再检查 State。

---

# 20. Stale Event 定义

以下任一条件成立，通常判定为 Stale：

- `workflowId` 属于旧 Workflow。
- `resolutionCycleId` 属于旧 Cycle。
- `actionId` 已 Invalidated 或被替换。
- `approvalId` 属于旧 Action。
- `toolExecutionId` 不是当前 Execution。
- `verificationId` 不是当前 Verification。
- `resolutionAttemptId` 属于旧 Attempt。
- Ticket 已进入 Terminal State，事件属于旧处理周期。
- 业务效果已被更新的事实取代。

处理：

```text
记录 Processed Event = STALE
写 Audit / Metric
Commit
ACK
```

Stale 不是 Infrastructure Failure，不应无限 Retry。

---

# 21. Out-of-order Event 定义

Event 本身可能属于当前业务周期，但必要前置事实尚未应用。

示例：

```text
tool.execution.completed
在 Ticket 仍为 WAITING_FOR_APPROVAL 时到达
且 actionId、approvalId、workflowId 都匹配当前业务意图
```

这可能意味着：

```text
approval.granted 事件尚未到达或尚未处理
```

处理：

```text
bounded retry
→ reconciliation
→ DLQ
```

不能直接跨过状态机前置步骤。

---

# 22. Corrupt / Suspicious Event

以下情况不能仅作为 Stale：

- Event `ticketId` 与 Action 所属 Ticket 不一致。
- 同一个 `toolExecutionId` 指向不同 Action。
- Approval 的 ActionType 与 Pending Action 不一致。
- EventId 重用但 Payload 不同。
- Payload 包含禁止的 Secret。
- 同一 VerificationId 指向不同 Resolution Attempt。

处理：

```text
Immediate DLQ
Security / Integrity Alert
不更新 Ticket
```

---

# 23. Event Ordering 使用 Aggregate Version

## 23.1 Producer Aggregate Version

Ticket Workflow 发布事件时：

```text
aggregateVersion = Ticket 提交后的 Version
```

同一事务多个事件使用相同 Aggregate Version，利用：

```text
sequence = 0, 1, 2...
```

## 23.2 Consumer 对外部 Event

外部服务的 Aggregate Version 不等于 Ticket Version。

例如：

```text
approval.aggregateVersion
toolExecution.aggregateVersion
```

不能直接与 `ticket.version` 比较。

必须按：

```text
producer
aggregateType
aggregateId
aggregateVersion
```

理解。

## 23.3 Consumer Ordering State

MVP 不维护所有外部 Aggregate 的完整 Last Seen Version 表。

依赖：

- EventId 去重
- Stable Business IDs
- Ticket State
- 当前 Workflow / Action / Attempt
- 必要时 Retry / Reconciliation

未来如有需要可增加：

```text
external_aggregate_offsets
```

---

# 24. Multi-instance API 并发

多个 Ticket Service 实例可以同时接收请求。

不使用 JVM 本地锁作为正确性机制。

正确性依赖：

```text
PostgreSQL Unique Constraint
Optimistic Lock
Idempotency Record
Partial Unique Index
```

本地 `synchronized` 仅可用于性能优化，不能作为唯一保护。

---

# 25. Multi-instance Consumer 并发

## MVP

状态修改 Queue 建议：

```text
x-single-active-consumer = true
```

降低同一 Queue 内并发。

但系统仍必须支持：

- Consumer Failover
- 不同 Queue 同时处理同一 Ticket
- Scheduler 与 Consumer 竞争
- API 与 Consumer 竞争

因此数据库并发控制仍是 Source of Truth。

## Future Scale

可以使用：

```text
consistent-hash exchange
partition key = ticketId
```

或多个分区 Queue。

即使分区，也保留 Optimistic Lock 和 Idempotency。

---

# 26. Database Constraint 防线

关键约束：

```text
UNIQUE(actor_scope, idempotency_key)
PRIMARY KEY(consumer_name, event_id)
UNIQUE(ticket_id, aggregate_version) on status history
Partial UNIQUE: one open user request
Partial UNIQUE: one active pending action
Partial UNIQUE: one active SLA cycle
UNIQUE(ticket_id, cycle_number)
UNIQUE(tool_execution_id)
```

数据库 Constraint Violation 处理：

1. Rollback。
2. Reload。
3. 判断是否是并发产生的已完成结果。
4. 如果等价：Idempotent Success。
5. 如果不等价：Business Conflict / Integrity Alert。

---

# 27. Race Decision：Create Ticket Duplicate

## 场景

两个相同 Create Request 同时提交相同 Idempotency-Key。

## 结果

- 一个成功 Reserve。
- 一个命中 Unique Conflict。
- 第二个读取第一条记录。
- 如果第一请求完成：返回同一 Ticket。
- 如果执行中：`REQUEST_IN_PROGRESS`。

禁止生成两个 Ticket。

---

# 28. Race Decision：Add Message Duplicate

## 相同 Idempotency-Key

只创建一个 Message。

## 不同 Key、相同内容

默认认为是两个独立 Message，不做 Content-based 去重。

原因：

- 用户可能有意重复强调。
- Content Hash 去重可能误删合法信息。

如果 Message 同时用于回答 Open User Request：

- 第一个合法 Message 恢复 Ticket。
- 第二个 Message 可以保存为普通 Message。
- 第二个不得再次 Resume Workflow。

---

# 29. Race Decision：Cancel vs Approval Granted

## Cancel 先提交

```text
Ticket → CANCELLED
Pending Action → INVALIDATED
approval.granted → STALE / REJECTED_BUSINESS_RULE
ACK
```

## Approval 先提交

```text
Ticket → EXECUTING
Cancel 重新加载后失败
CANCELLATION_NOT_ALLOWED
```

唯一胜者由 Optimistic Lock 决定。

---

# 30. Race Decision：Cancel vs Auto-approved Action

与 Approval 类似。

如果 `policy.action_auto_approved` 先提交：

```text
Ticket → EXECUTING
Cancel 不可直接执行
```

如果 Cancel 先提交：

```text
Auto-approved Event 记为 Stale
```

---

# 31. Race Decision：Approval Granted vs Rejected

相同 `approvalId` 不应同时产生 Granted 和 Rejected。

如果发生：

- 第一个合法结果提交。
- 第二个结果检测到 Approval 已终结。
- 如果 Producer 语义冲突：
  - `APPROVAL_TERMINAL_RESULT_CONFLICT`
  - DLQ 或 Security Review。
- 不允许第二个结果反转 Ticket。

---

# 32. Race Decision：Approval Granted vs Expired

## Granted 先提交，且 `approvedAt <= expiresAt`

进入 EXECUTING。

迟到 Expired Event：

```text
Business Duplicate / Stale
ACK
```

## Expired 先提交

Ticket 返回 INVESTIGATING，Pending Action = EXPIRED。

之后 Granted：

```text
Reject
ACK + Audit
```

即使 Granted Event 的 Broker 到达时间较晚，也依据 `approvedAt` 和已提交终态判断。

---

# 33. Race Decision：Tool Success vs Tool Failure

同一：

```text
toolExecutionId + executionAttemptId
```

只能有一个 Terminal Result。

第一个结果提交。

第二个不同 Terminal Result：

```text
TOOL_TERMINAL_RESULT_CONFLICT
Immediate DLQ
Integrity Alert
```

不能把后到结果简单视为普通 Stale。

---

# 34. Race Decision：Tool Success vs Result Unknown

如果 Result Unknown 先提交：

```text
Ticket → ESCALATED
```

之后 Success：

- 保留为新 Evidence。
- 默认不自动从 ESCALATED 进入 VERIFYING。
- 需要 Reconciliation / Human Resume。
- 记录 Tool Result Conflict。

如果 Success 先提交：

```text
Ticket → VERIFYING
```

之后 Unknown：

- 不反转状态。
- 记录冲突和告警。
- 根据安全策略可能 Escalate，但必须通过显式命令，不在 Consumer 中静默覆盖。

---

# 35. Race Decision：Verification Success vs Failure

同一 `verificationId` 只能有一个 Terminal Result。

冲突结果：

```text
VERIFICATION_TERMINAL_RESULT_CONFLICT
DLQ
Integrity Alert
```

不同 `verificationId`：

- 只有当前 Verification 可以影响 Ticket。
- 旧 Verification 为 Stale。
- 新 Verification 必须属于当前 Attempt。

---

# 36. Race Decision：Verification Success vs Reopen

正常情况下只有 RESOLVED / CLOSED 才可 Reopen，因此 Reopen 不应与 VERIFYING 并发成功。

如果客户端基于旧 Snapshot 发 Reopen：

```text
If-Match Conflict
412 CONCURRENT_UPDATE
```

如果 Verification Success 先提交：

- Ticket → RESOLVED。
- 用户重新读取后可显式 Reopen。

---

# 37. Race Decision：Reopen vs Auto-close

## Reopen 先提交

```text
RESOLVED → INVESTIGATING
Version increments
Auto-close conflict
Reload → no-op
```

## Auto-close 先提交

```text
RESOLVED → CLOSED
```

Reopen Reload：

- 7 天内允许 CLOSED → INVESTIGATING。
- 使用新 Version 提交。

最终可能是 CLOSED 或 INVESTIGATING，取决于串行化顺序，两者均合法。

---

# 38. Race Decision：Confirm Resolution vs Auto-close

两者都执行：

```text
RESOLVED → CLOSED
```

第一个提交。

第二个 Reload 后：

- 如果 Close Reason 业务上可接受等价：
  - 返回 Idempotent Success。
- 不再写第二条 History 或 Outbox。

原始 `closeReason` 保持第一个提交结果。

---

# 39. Race Decision：Close vs Reopen

如果 Close 先提交：

- Reopen 可以在 7 天内继续执行。

如果 Reopen 先提交：

- Close 基于旧 Version 失败。
- Reload 后 Ticket 已 INVESTIGATING。
- Close 返回 Invalid State。

---

# 40. Race Decision：Two Assignments

两名 Support 同时 Assign 到不同用户：

- 两者读取相同 Version。
- 一个提交。
- 另一个 Conflict。
- 第二个 Reload 后不自动覆盖。
- 返回 `CONCURRENT_UPDATE`，要求用户确认最新 Assignment。

Assignment 不使用 Last-write-wins。

---

# 41. Race Decision：Assignment vs Escalation

两者可以同时修改 Ticket Snapshot。

默认使用相同 Ticket Version，只有一个先提交。

第二个 Reload 后重新判断：

- 如果 Escalation 允许保留 Assignment，可基于新状态重新提交。
- 如果 Escalation Target 会替换 Team，则按照 Escalation Policy 生成新 Assignment History。
- 不允许静默覆盖。

---

# 42. Race Decision：User Reply vs Cancel

## User Reply 先提交

```text
WAITING_FOR_USER → INVESTIGATING
```

Cancel Reload 后可从 INVESTIGATING 继续 Cancel，若用户仍坚持取消。

## Cancel 先提交

Message 可以按产品策略：

- 保存为 Terminal Ticket 的普通 Requester Message；或
- 返回 Invalid State。

MVP 推荐：

```text
保存 Message
不恢复 Workflow
不改变 CANCELLED
```

必须清楚返回：

```text
ticketStatus = CANCELLED
workflowResumeRequested = false
```

---

# 43. Race Decision：Multiple User Replies

对同一个 Open Request：

- 第一个匹配 `requestId` 的有效回复将 Request 标记为 ANSWERED 并 Resume。
- 后续回复保存为普通 Message。
- 不再次改变状态。
- 不再次发布 Resume Event。

Partial Unique 与 Request Status 防止重复 Answer。

---

# 44. Race Decision：Agent Workflow Failed vs Tool Result

如果 Tool 已开始：

- `agent.workflow.failed` 不能简单把 Ticket 变为 FAILED。
- 若 Tool Result 未知，Escalate。
- 若 Tool Success 已提交，继续 VERIFYING。
- 旧 Workflow Failure 视为 Stale 或 Evidence。

Failure Consumer 必须检查 Pending Action 和 Tool Execution 状态。

---

# 45. Race Decision：SLA Breach vs Resolve

## Resolve 先提交

SLA → MET。

Breach Job Reload 后停止。

## Breach 先提交

SLA → BREACHED，但仍是 Active Cycle。

之后 Resolve：

```text
SLA 保持有 breachedAt
状态可以变为 MET 或 MET_AFTER_BREACH
```

当前 Data Model 只有 `MET` 和 `BREACHED`。

MVP 决策：

```text
SLA.status = MET
breached_at 保留
met_at 写入
```

这样表示最终完成但曾经 Breach。

---

# 46. Race Decision：Multiple Auto-close Workers

每个 Job 使用：

```text
jobKey = auto-close:{ticketId}:{resolutionCycleId}
```

并使用 Expected Version。

- 一个 Worker 成功关闭。
- 其他 Worker Reload 后返回 Idempotent Success。
- 不重复写 History 或 Event。

不依赖 JVM Local Lock。

---

# 47. Race Decision：Multiple Outbox Publishers

使用：

```sql
FOR UPDATE SKIP LOCKED
```

Claim。

正常情况下一个 Row 只被一个实例 Claim。

如果 Lock Timeout 后重复 Claim，可能重复发布同一 EventId。

Consumer 必须去重。

---

# 48. Retry 分类

## 48.1 可以自动 Retry

- Database transient failure
- Deadlock
- Serialization failure
- Optimistic Conflict，且重新评估后仍合法
- Out-of-order Event
- RabbitMQ transient failure
- Publisher Confirm timeout

## 48.2 不可自动 Retry

- Invalid Schema
- Invalid Authorization
- Business Rule Failure
- EventId Payload Conflict
- Tool Terminal Result Conflict
- Verification Terminal Result Conflict
- Secret Leakage
- Reopen Window Expired
- Cancel during active unknown side effect

---

# 49. Retry Budget

## API

客户端负责基于错误码决定重试。

服务内部数据库瞬时重试：

```text
最多 3 次
```

## Event Consumer

- 立即应用级重试：最多 3 次短 Backoff。
- RabbitMQ Retry Queue：5s、30s、5m。
- 之后 DLQ。

## Scheduler

每次扫描自然重试。

单 Ticket 失败不阻塞 Batch。

## Outbox Publisher

最多 10 次自动 Publish Attempt，之后 Alert 并保留 Row。

---

# 50. Retry 必须保持的 Identity

Retry 过程中不得改变：

```text
idempotencyKey
commandId
eventId
ticketId
workflowId
actionId
toolExecutionId
verificationId
resolutionAttemptId
jobKey
```

改变 Event Payload 时必须创建新 EventId，并使用 Correction 语义。

---

# 51. Reconciliation

以下情况需要 Reconciliation，而不是继续盲目 Retry：

- Tool Result Unknown 后又收到 Success。
- Event 长期 Out-of-order。
- Idempotency Record 长期 IN_PROGRESS。
- Outbox Event 长期 Unpublished。
- Approval 出现终态冲突。
- Verification 出现终态冲突。
- Ticket Snapshot 与 History Version 不一致。

Reconciliation 输出：

```text
NO_ACTION
MARK_STALE
REAPPLY_SAFE
ESCALATE
CREATE_CORRECTION_EVENT
MANUAL_REVIEW_REQUIRED
```

所有 Reconciliation 必须记录 Audit。

---

# 52. Generic Event Classification Algorithm

```text
function classify(event, ticket, processedRecord):
    if processedRecord exists:
        if processedRecord.payloadHash != event.payloadHash:
            return CORRUPT_EVENT_ID_REUSE
        return DUPLICATE

    if event.ticketId != ticket.id:
        return CORRUPT_REFERENCE

    if event.resolutionCycleId exists
       and event.resolutionCycleId != ticket.currentResolutionCycleId:
        return STALE

    if event.workflowId exists
       and event.workflowId != ticket.activeWorkflowId:
        return STALE

    if event action/attempt references conflict:
        return CORRUPT_REFERENCE or STALE

    if business effect already applied:
        return BUSINESS_DUPLICATE

    if source state is legal:
        return APPLY

    if current references match and predecessor may be missing:
        return OUT_OF_ORDER

    if ticket is terminal and event belongs to completed cycle:
        return STALE

    return REJECTED_BUSINESS_RULE
```

---

# 53. Approval Event Classification

```text
Duplicate EventId
→ DUPLICATE

Same approvalId already applied
→ BUSINESS_DUPLICATE

Old workflow/action
→ STALE

Wrong action type for current action
→ CORRUPT_REFERENCE

Ticket WAITING_FOR_APPROVAL and valid
→ APPLY

Ticket INVESTIGATING with same current pending action,
possible predecessor missing
→ OUT_OF_ORDER or RECONCILE

Ticket CANCELLED/CLOSED
→ STALE / REJECTED
```

---

# 54. Tool Event Classification

```text
Same toolExecutionId + same terminal result
→ BUSINESS_DUPLICATE

Same executionAttemptId + conflicting terminal result
→ TERMINAL_RESULT_CONFLICT

Old workflow/action/execution
→ STALE

Ticket EXECUTING and refs match
→ APPLY

Ticket WAITING_FOR_APPROVAL and refs match
→ OUT_OF_ORDER

Ticket ESCALATED after UNKNOWN result
→ EVIDENCE_ONLY / RECONCILE

Ticket CLOSED/CANCELLED
→ STALE
```

---

# 55. Verification Event Classification

```text
Same verificationId + same result
→ BUSINESS_DUPLICATE

Same verificationId + conflicting result
→ TERMINAL_RESULT_CONFLICT

Old workflow/cycle/attempt
→ STALE

Ticket VERIFYING and refs match
→ APPLY

Ticket EXECUTING and Verification arrives early
→ OUT_OF_ORDER

Ticket RESOLVED by same verificationId
→ BUSINESS_DUPLICATE

Ticket reopened to new cycle
→ STALE
```

---

# 56. API Error Semantics

| 情况 | HTTP | Error Code |
|---|---:|---|
| Same Key + Same Completed Request | 原始状态码 | 无错误 |
| Same Key + Different Payload | 409 | IDEMPOTENCY_KEY_REUSED |
| Same Key Still Processing | 409 | REQUEST_IN_PROGRESS |
| Expected Version Mismatch | 412 | CONCURRENT_UPDATE |
| State changed and command no longer legal | 422 | INVALID_STATE_TRANSITION |
| Equivalent result already committed | 200/201 | Idempotent Replay |
| Integrity Conflict | 409/500 | DATA_INTEGRITY_CONFLICT |

---

# 57. Event ACK / Retry / DLQ Semantics

| Classification | Broker Result |
|---|---|
| APPLY | Commit then ACK |
| DUPLICATE | ACK |
| BUSINESS_DUPLICATE | Commit processed result then ACK |
| STALE | Commit stale record then ACK |
| REJECTED_BUSINESS_RULE | ACK or DLQ by policy |
| OUT_OF_ORDER | Retry |
| TRANSIENT_FAILURE | Retry |
| CORRUPT_REFERENCE | DLQ |
| EVENT_ID_REUSE | DLQ |
| TERMINAL_RESULT_CONFLICT | DLQ |
| SECRET_DETECTED | DLQ |

---

# 58. Observability

## Traces

推荐 Span：

```text
ticket.idempotency.reserve
ticket.idempotency.replay
ticket.concurrency.update
ticket.event.classify
ticket.event.deduplicate
ticket.reconciliation.execute
```

Attributes：

```text
opsmind.use_case_id
opsmind.event_type
opsmind.classification
opsmind.retry_count
opsmind.version_before
opsmind.version_after
opsmind.idempotency_replayed
opsmind.conflict_type
```

## Metrics

```text
ticket_command_idempotency_replay_total
ticket_command_idempotency_conflict_total
ticket_command_in_progress_total
ticket_optimistic_conflict_total
ticket_optimistic_retry_total
ticket_business_duplicate_total
ticket_event_duplicate_total
ticket_event_stale_total
ticket_event_out_of_order_total
ticket_event_terminal_conflict_total
ticket_event_reference_corruption_total
ticket_reconciliation_total
ticket_reconciliation_manual_review_total
```

Allowed Labels：

```text
operation
event_type
classification
result
conflict_type
```

禁止：

```text
ticket_id
event_id
workflow_id
idempotency_key
actor_id
```

---

# 59. Alert

## Critical

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD > 0
TOOL_TERMINAL_RESULT_CONFLICT > 0
VERIFICATION_TERMINAL_RESULT_CONFLICT > 0
CORRUPT_REFERENCE > 0
Reconciliation Manual Review backlog growing
```

## Warning

```text
Optimistic conflict rate unusually high
Out-of-order rate unusually high
Idempotency IN_PROGRESS stale count > threshold
Duplicate rate sudden spike
```

---

# 60. Security

- Idempotency-Key 不写入普通日志，只记录 Hash。
- Actor Scope 必须来自认证上下文，不能完全信任客户端。
- Event Payload Hash 使用 Canonical JSON。
- EventId Payload Conflict 视为安全问题。
- 不允许用户通过重放旧 If-Match 绕过当前状态。
- Replay / Reconciliation 必须记录 Operator。
- 对 Corrupt Reference 不自动修正数据。
- Secret Detection 失败必须 Fail-closed。

---

# 61. Unit Test 要求

```text
shouldCanonicalizeEquivalentJsonRequestsToSameHash
shouldTreatNullAndMissingFieldAccordingToSchema
shouldRejectSameIdempotencyKeyWithDifferentHash
shouldReturnStoredResponseForCompletedRequest
shouldReturnRequestInProgressForFreshReservation
shouldReconcileStaleInProgressRecord

shouldClassifySameEventIdAndHashAsDuplicate
shouldClassifySameEventIdDifferentHashAsCorrupt
shouldClassifyOldWorkflowEventAsStale
shouldClassifyMissingPredecessorAsOutOfOrder
shouldClassifyConflictingToolTerminalResultAsCorrupt
shouldClassifySameVerificationResultAsBusinessDuplicate

shouldReloadAndReevaluateAfterOptimisticConflict
shouldNotBlindlyRetryInvalidTransition
shouldPreserveIdentityAcrossRetry
```

---

# 62. Integration Test 要求

```text
shouldCreateOnlyOneTicketForConcurrentIdenticalRequests
shouldCreateTwoTicketsForDifferentIdempotencyKeys
shouldAllowOnlyOneConcurrentAssignment
shouldResolveCancelApprovalRace
shouldResolveApprovalExpiryGrantRace
shouldRejectConflictingToolResults
shouldRejectConflictingVerificationResults
shouldResumeWorkflowOnlyOnceForMultipleReplies
shouldCloseOnlyOnceForMultipleAutoCloseWorkers
shouldHandleReopenAutoCloseRace
shouldKeepOldVerificationFromAffectingReopenedCycle
shouldDeduplicateEventAcrossConsumerRestart
shouldHandleDifferentQueuesUpdatingSameTicket
shouldEnforceOneActivePendingActionUnderConcurrency
shouldEnforceOneOpenUserRequestUnderConcurrency
```

---

# 63. Load / Stress Test

至少模拟：

```text
100 concurrent Create Ticket requests with same key
100 concurrent Assign requests to same Ticket
Approval and Cancel repeated in random order
Tool success/failure/unknown events shuffled
Verification events from old and current cycles mixed
Multiple consumers and schedulers active
Outbox duplicate publication
```

验证：

- 无重复业务资源
- 无 Lost Update
- 无非法状态
- History Version 连续
- Outbox 与状态一致
- 无 Deadlock 无限重试
- Duplicate / Stale Metrics 符合预期

---

# 64. 被拒绝的方案

## 64.1 Last-write-wins

拒绝，因为会静默覆盖并发操作。

## 64.2 仅靠前端禁用按钮

拒绝，因为网络重试和多客户端仍会重复。

## 64.3 仅靠 RabbitMQ `redelivered` Flag

拒绝，因为 Producer 也可能重复发布。

## 64.4 只按 TicketId 判定 Event 是否有效

拒绝，因为 Reopen 后同一 Ticket 有多个 Workflow 与 Cycle。

## 64.5 使用 Message Content Hash 自动去重所有用户回复

拒绝，因为相同内容可能是合法的重复表达。

## 64.6 JVM Local Lock

拒绝作为正确性机制，因为多实例无法共享。

## 64.7 对 Optimistic Conflict 无条件重试

拒绝，因为业务状态可能已变化。

## 64.8 将所有冲突都视为 Stale 并 ACK

拒绝，因为 Terminal Result Conflict 和 Reference Corruption 必须告警和 DLQ。

## 64.9 使用分布式锁替代业务 Idempotency

拒绝。锁过期、进程崩溃和重复事件仍然存在。

---

# 65. 验收标准

- [x] 并发来源已定义。
- [x] Command Idempotency Scope 已定义。
- [x] Canonical Request Hash 已定义。
- [x] HTTP Idempotency Algorithm 已定义。
- [x] Stale IN_PROGRESS Recovery 已定义。
- [x] Event Deduplication 已定义。
- [x] Business Duplicate 已定义。
- [x] Aggregate Version 与 Expected Version 已定义。
- [x] Optimistic Lock Retry 已定义。
- [x] Duplicate / Stale / Out-of-order 判定顺序已定义。
- [x] Corrupt Event 和 Terminal Result Conflict 已定义。
- [x] Multi-instance API、Consumer、Scheduler 和 Publisher 策略已定义。
- [x] Cancel、Approval、Tool、Verification、Reopen、Auto-close 等竞态已冻结。
- [x] Retry Budget 与 Reconciliation 已定义。
- [x] API 与 Broker 错误语义已定义。
- [x] Observability、Alert、Security 和测试要求已定义。

---

# 66. 下一步

下一份文档建议：

```text
10-failure-handling/README_CN.md
10-failure-handling/README_EN.md
```

该文档将进一步定义：

- Domain、Application、Infrastructure Error Taxonomy
- Retryable 与 Non-retryable Failure
- Reconciliation Workflow
- DLQ Triage
- Manual Recovery
- Compensating Action
- User-visible Error 与 Internal Error 的映射
