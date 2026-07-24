# OpsMind Ticket Workflow — 02 Business Invariants

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Business Invariants  
> **版本：** 1.0  
> **状态：** Proposed  
> **依赖：** `01-domain-model/README_CN.md`  
> **建议路径：** `docs/low-level-design/domains/02-ticket-workflow/02-business-invariants/README_CN.md`

---

## 1. 文档目的

本文档定义 OpsMind Ticket Workflow 中任何实现都不能违反的业务不变量。

这些不变量将直接约束：

- `Ticket` Aggregate
- `TicketMessage` Aggregate
- `TicketSla` Aggregate
- Application Service
- API Command Handler
- RabbitMQ Event Consumer
- Transaction Boundary
- Security Check
- Idempotency Logic
- State Machine
- Unit Test
- Integration Test
- Failure Recovery

本文档不描述完整状态转换矩阵。状态转换细节将在 `03-state-machine/README_CN.md` 中定义。

---

# 2. 什么是不变量

业务不变量是指：

> 无论请求来自前端、内部 API、RabbitMQ Event、后台任务、人工操作还是 Agent Runtime，系统状态都必须始终满足的规则。

例如：

```text
Verification 成功前，Ticket 不能进入 RESOLVED。
```

这条规则不能只在前端检查，也不能只依赖 Agent 自觉遵守。

它必须由 Ticket Domain 强制执行。

---

# 3. 不变量执行层级

## 3.1 Domain Invariant

由 Aggregate 或纯 Domain Policy 强制执行。

例如：

```text
CANCELLED Ticket 不能进入 EXECUTING。
```

## 3.2 Application Invariant

需要协调多个 Aggregate、Repository 或外部引用。

例如：

```text
添加用户回复时，Message 与 Ticket 状态变化必须在同一业务事务中保存。
```

## 3.3 Security Invariant

由 Authentication / Authorization Layer 强制执行。

例如：

```text
Employee 只能 Reopen 自己的 Ticket。
```

## 3.4 Integration Invariant

由 Event Consumer、Outbox 和 Idempotency 机制强制执行。

例如：

```text
重复 approval.granted 不能重复推动 Ticket。
```

## 3.5 Persistence Invariant

由数据库 Constraint、Unique Index 和 Optimistic Lock 支持。

例如：

```text
同一 consumer_name 和 event_id 只能处理一次。
```

---

# 4. 错误处理原则

违反不变量时，系统不得静默修复或继续执行。

推荐错误码：

```text
INVALID_TICKET_STATE
INVALID_STATE_TRANSITION
ACTIVE_WORKFLOW_ALREADY_EXISTS
WORKFLOW_REFERENCE_MISMATCH
APPROVAL_REFERENCE_MISMATCH
ACTION_REFERENCE_MISMATCH
VERIFICATION_REQUIRED
VERIFICATION_REFERENCE_MISMATCH
TICKET_ALREADY_CANCELLED
TICKET_ALREADY_CLOSED
REOPEN_NOT_ALLOWED
CANCELLATION_NOT_ALLOWED
CONCURRENT_UPDATE
DUPLICATE_COMMAND
STALE_EVENT
OUT_OF_ORDER_EVENT
FORBIDDEN
```

---

# 5. Ticket Identity 不变量

## BI-001 Ticket 必须具有唯一内部 ID

```text
Ticket.id != null
```

要求：

- 使用 UUID 或 ULID。
- 内部 ID 全局唯一。
- 不能依赖数据库顺序 ID 进行跨服务关联。

执行层级：

```text
Domain + Persistence
```

## BI-002 Ticket 必须具有唯一 Display ID

例如：

```text
INC-2048
```

要求：

- 用户可读。
- 在 Ticket Workflow 范围内唯一。
- 创建后不可修改。
- 与内部 `TicketId` 分离。

执行层级：

```text
Domain + Unique Constraint
```

## BI-003 Ticket 必须具有 Requester

```text
requesterId != null
```

规则：

- RequesterId 创建后不可替换。
- 账号删除不应删除历史 Ticket。
- Ticket Domain 不保存完整用户 Profile。

执行层级：

```text
Domain
```

## BI-004 Ticket 创建时间不可缺失或修改

```text
createdAt != null
```

规则：

- 创建后不可修改。
- 使用 UTC。
- 后续时间不能早于 `createdAt`。

执行层级：

```text
Domain
```

---

# 6. Ticket 内容不变量

## BI-005 Title 必须合法

要求：

```text
trimmed
1–200 characters
not blank
no control characters
```

执行层级：

```text
Value Object
```

## BI-006 Initial Description 必须合法

要求：

```text
1–10000 characters
not blank
sanitized before display
classified as Sensitive
```

执行层级：

```text
Value Object + API Validation
```

## BI-007 ApplicationCode 必须来自允许集合

MVP：

```text
HOUSING_PORTAL
EMAIL
VPN
OTHER
```

未知值不能直接写入 Domain。

执行层级：

```text
Value Object
```

## BI-008 Category 与 Subcategory 必须匹配

示例：

```text
IDENTITY_ACCESS
├── MFA_FAILURE
├── ACCOUNT_LOCKED
├── GROUP_MEMBERSHIP
└── SESSION_FAILURE
```

禁止：

```text
category = NETWORK
subcategory = MFA_FAILURE
```

执行层级：

```text
Domain Policy
```

## BI-009 Category 变更必须保留历史

Category 不能被无痕覆盖。

每次变更必须记录：

```text
oldCategory
newCategory
reason
source
changedAt
```

执行层级：

```text
Application + Append-only History
```

---

# 7. Ticket 生命周期不变量

## BI-010 Ticket 状态只能通过 Domain Behavior 改变

禁止：

```java
ticket.setStatus(...)
```

必须通过：

```text
startTriaging()
startInvestigation()
waitForApproval()
startVerification()
resolve()
close()
cancel()
reopen()
escalate()
```

执行层级：

```text
Domain
```

## BI-011 每次状态变化必须写入 Status History

一次合法状态变化必须原子地完成：

```text
Update Ticket
+
Insert TicketStatusHistory
+
Insert Outbox Event
```

任何一个失败，整个事务回滚。

执行层级：

```text
Application Transaction
```

## BI-012 fromStatus 必须与当前状态一致

不能写入伪造 History：

```text
history.fromStatus != ticket.status before transition
```

执行层级：

```text
Domain + Application
```

## BI-013 状态变化必须递增 Aggregate Version

每次修改 Ticket 核心状态：

```text
version = version + 1
```

执行层级：

```text
Persistence + Domain
```

## BI-014 Terminal State 不能被普通后台事件推进

Terminal States：

```text
CLOSED
CANCELLED
```

这些状态不能因迟到的：

```text
approval.granted
tool.execution.completed
verification.completed
```

而继续向前推进。

执行层级：

```text
Domain + Event Consumer
```

## BI-015 CLOSED 不能被后台事件自动 Reopen

Reopen 必须是显式业务操作，并记录：

```text
actor
reason
occurredAt
```

执行层级：

```text
Domain + Security
```

## BI-016 FAILED 不能被当作隐式 RESOLVED

失败不等于问题已解决。

`FAILED` 后必须进入：

```text
retry
investigation
escalation
或 explicit cancellation
```

执行层级：

```text
State Machine
```

---

# 8. Active Workflow 不变量

## BI-017 同一 Ticket 同时最多一个 Active Workflow

```text
activeWorkflowId = null
或
exactly one WorkflowId
```

禁止：

```text
workflow A active
workflow B active
```

执行层级：

```text
Domain + Persistence
```

## BI-018 新 Workflow 不能覆盖现有 Active Workflow

只有当前 Workflow 已：

```text
COMPLETED
FAILED
CANCELLED
TIMED_OUT
```

并完成业务处理后，才允许关联新 Workflow。

执行层级：

```text
Domain
```

## BI-019 Workflow Reference 必须属于当前 Ticket

所有 Workflow Event 必须满足：

```text
event.ticketId == ticket.id
event.workflowId == ticket.activeWorkflowId
```

不匹配时：

```text
reject as WORKFLOW_REFERENCE_MISMATCH
```

执行层级：

```text
Event Consumer + Domain
```

## BI-020 取消 Ticket 后不能关联新 Workflow

除非先通过明确的 `reopen()` 进入合法状态。

执行层级：

```text
Domain
```

## BI-021 CLOSED Ticket 不能关联新 Workflow

只能先显式 Reopen，再创建或关联新 Workflow。

执行层级：

```text
Domain
```

## BI-022 Reopen 后必须有新的调查路径

Reopen 必须：

```text
创建新 Workflow
或
明确恢复允许恢复的旧 Workflow
```

MVP 推荐：

```text
Reopen 创建新的 WorkflowId
```

执行层级：

```text
Application
```

---

# 9. User Interaction 不变量

## BI-023 WAITING_FOR_USER 必须有关联 Request

Ticket 进入 `WAITING_FOR_USER` 时必须保存：

```text
requestId
reasonCode
requestedAt
workflowId
```

执行层级：

```text
Domain
```

## BI-024 用户回复必须引用有效 Ticket

Message 的：

```text
message.ticketId == ticket.id
```

执行层级：

```text
Application
```

## BI-025 用户回复只能恢复等待中的 Ticket

默认规则：

```text
WAITING_FOR_USER
→ INVESTIGATING
```

如果 Ticket 已：

```text
CLOSED
CANCELLED
```

用户 Message 可以保存为记录，但不能自动恢复 Ticket。

执行层级：

```text
Domain + Application
```

## BI-026 Message 创建后不可被普通更新覆盖

Message 采用 Immutable / Append-only 原则。

修改内容必须通过：

```text
redaction
correction message
audited visibility change
```

执行层级：

```text
TicketMessage Aggregate
```

## BI-027 Internal Message 不得暴露给 Requester

```text
visibility = INTERNAL_SUPPORT_ONLY
```

不能通过 Employee API 返回。

执行层级：

```text
Query Authorization
```

---

# 10. Pending Action 不变量

## BI-028 同一 Ticket MVP 最多一个 Pending Action

```text
pendingAction == null
或
exactly one PendingActionReference
```

这样可以降低审批和 Tool Execution 的并发复杂度。

未来支持多个 Action 时必须新建 ADR。

执行层级：

```text
Domain
```

## BI-029 Pending Action 必须属于 Active Workflow

```text
pendingAction.workflowId == ticket.activeWorkflowId
```

执行层级：

```text
Domain
```

## BI-030 Pending Action 不能包含 Credential

禁止保存：

- Password
- Token
- API Key
- Session Cookie
- Private Key

执行层级：

```text
Domain Model + Security Review
```

## BI-031 Action Reference 创建后不可替换业务语义

审批期间不能把：

```text
RESET_DUO_ENROLLMENT
```

无痕替换成：

```text
ADD_ADMIN_ROLE
```

任何语义变化必须创建新 ActionId 和新 Approval。

执行层级：

```text
Domain + Policy
```

---

# 11. Approval 不变量

## BI-032 WAITING_FOR_APPROVAL 必须有 Approval Reference

```text
pendingAction.approvalId != null
```

执行层级：

```text
Domain
```

## BI-033 Approval 必须匹配 Ticket

```text
approval.ticketId == ticket.id
```

执行层级：

```text
Event Consumer
```

## BI-034 Approval 必须匹配 Active Workflow

```text
approval.workflowId == ticket.activeWorkflowId
```

执行层级：

```text
Event Consumer + Domain
```

## BI-035 Approval 必须匹配 Action

```text
approval.actionId == ticket.pendingAction.actionId
approval.actionType == ticket.pendingAction.actionType
```

执行层级：

```text
Domain
```

## BI-036 Approval 必须未过期

```text
approvedAt <= expiresAt
```

过期 Approval 不能推动 Ticket 进入 `EXECUTING`。

执行层级：

```text
Domain + Policy Integration
```

## BI-037 Approval Rejected 后不能继续执行同一 Action

Rejected Action 必须：

```text
clear
重新调查
提出新 Action
或 Escalate
```

执行层级：

```text
Domain
```

## BI-038 Approval Granted 不能被重复应用

重复事件返回 Idempotent Success，不重复：

- 改状态
- 写 History
- 发布 Event
- 创建 Tool Execution

执行层级：

```text
Event Idempotency + Domain
```

## BI-039 Approver 不能违反 Separation of Duties

如果 Policy 要求独立 Approver，则：

```text
approver != requester
approver != prohibited action proposer
```

具体身份验证在 Policy / Security 层完成。

执行层级：

```text
Security + Policy
```

---

# 12. Tool Execution 不变量

## BI-040 没有有效授权时不能进入 EXECUTING

对需要审批的 Action，必须存在有效 Approval。

低风险无需审批的 Action，必须有 Policy Decision：

```text
AUTO_APPROVED
```

执行层级：

```text
Domain + Policy Integration
```

## BI-041 Tool Execution 必须匹配 Pending Action

```text
toolExecution.actionId == pendingAction.actionId
toolExecution.actionType == pendingAction.actionType
```

执行层级：

```text
Event Consumer + Domain
```

## BI-042 Tool Execution 必须匹配当前 Workflow

```text
toolExecution.workflowId == activeWorkflowId
```

执行层级：

```text
Event Consumer
```

## BI-043 CANCELLED Ticket 不得启动新 Tool Execution

即使迟到的 Approval Granted 到达，也必须拒绝。

执行层级：

```text
Domain
```

## BI-044 CLOSED Ticket 不得启动 Tool Execution

必须先显式 Reopen。

执行层级：

```text
Domain
```

## BI-045 Tool Success 不得直接 Resolve Ticket

允许：

```text
EXECUTING
→ VERIFYING
```

禁止：

```text
EXECUTING
→ RESOLVED
```

执行层级：

```text
Domain + State Machine
```

## BI-046 Unknown Tool Result 必须进入安全状态

如果 Side Effect 是否发生未知：

```text
不能自动重试写操作
不能自动标记失败
不能自动 Resolve
```

必须：

```text
verify-before-retry
或 Escalate
```

执行层级：

```text
Application + Tool Integration
```

## BI-047 Write Tool 重试必须基于 Idempotency Key

同一业务 Action 的重复执行必须使用稳定：

```text
toolExecutionId
或 idempotencyKey
```

Ticket Domain 保存引用，不生成凭证。

执行层级：

```text
Tool Gateway
```

---

# 13. Verification 不变量

## BI-048 RESOLVED 必须有 Verification Evidence

进入 `RESOLVED` 必须满足：

```text
verificationId != null
verificationResult == SUCCESS
verifiedAt != null
```

执行层级：

```text
Domain
```

## BI-049 Verification 必须匹配 Ticket

```text
verification.ticketId == ticket.id
```

执行层级：

```text
Event Consumer
```

## BI-050 Verification 必须匹配 Active Workflow

```text
verification.workflowId == ticket.activeWorkflowId
```

执行层级：

```text
Event Consumer + Domain
```

## BI-051 Verification 必须对应最近一次 Tool / Resolution Attempt

迟到的旧 Verification 不能解决新一轮调查。

需要匹配：

```text
verification.attemptId
toolExecutionId 或 resolutionAttemptId
```

执行层级：

```text
Domain
```

## BI-052 Verification Failure 不能进入 RESOLVED

必须进入：

```text
INVESTIGATING
或 ESCALATED
```

执行层级：

```text
Domain + State Machine
```

## BI-053 重复 Verification Success 不能重复 Resolve

如果 Ticket 已被同一 VerificationId 解决：

```text
return idempotent success
```

执行层级：

```text
Event Idempotency + Domain
```

## BI-054 Verification 必须独立于 Resolution Proposal

提出解决方案的 Agent 不能仅凭自身声明完成 Verification。

MVP 至少逻辑上使用独立 Verification Agent / Step。

执行层级：

```text
Agent Runtime Design
```

---

# 14. Resolution 与 Closure 不变量

## BI-055 Resolution 必须包含最小必要字段

```text
resolutionCode
summary
rootCauseCode
verificationId
resolvedAt
resolvedBy
```

执行层级：

```text
Value Object
```

## BI-056 resolvedAt 只能设置一次

除非 Ticket Reopen 后进入新一轮 Resolution。

历史 Resolution 不得覆盖。

执行层级：

```text
Domain + History
```

## BI-057 RESOLVED 不等于 CLOSED

`RESOLVED` 表示系统认为问题已解决。

`CLOSED` 表示 Ticket 生命周期正式结束。

执行层级：

```text
Domain Model
```

## BI-058 Close 只能从允许状态发生

MVP 推荐：

```text
RESOLVED → CLOSED
```

Escalated Ticket 的人工关闭规则将在状态机中单独定义。

执行层级：

```text
Domain
```

## BI-059 Close 必须有 Close Reason 与 Actor

```text
closeReason != null
closedBy != null
closedAt != null
```

执行层级：

```text
Domain
```

## BI-060 CLOSED Ticket 不得修改核心业务字段

禁止修改：

- Requester
- Status
- Category
- Active Workflow
- Pending Action
- Resolution

除非通过显式 Reopen。

执行层级：

```text
Domain
```

---

# 15. Reopen 不变量

## BI-061 Reopen 必须来自允许状态

MVP 候选：

```text
RESOLVED
CLOSED
```

最终范围由状态机冻结。

执行层级：

```text
Domain
```

## BI-062 Reopen 必须包含 Reason

```text
reopenReason != null
```

执行层级：

```text
Domain
```

## BI-063 Reopen 必须包含合法 Actor

允许：

- Requester
- IT Support
- System Rule

不允许未经授权的其他用户。

执行层级：

```text
Security + Domain
```

## BI-064 Reopen 不能覆盖旧 Resolution

旧 Resolution 必须进入历史。

新的处理周期使用：

```text
new workflow
new verification
new resolution
```

执行层级：

```text
Application + History
```

## BI-065 Reopen 后必须清理旧 Pending Action

不能复用上一个周期中过期或已消费的 Approval。

执行层级：

```text
Domain
```

## BI-066 Reopen 必须触发新调查

推荐：

```text
RESOLVED / CLOSED
→ REOPENED
→ INVESTIGATING
```

或：

```text
Reopen Event
→ INVESTIGATING
```

最终由状态机决定。

执行层级：

```text
State Machine
```

---

# 16. Cancellation 不变量

## BI-067 Cancel 必须包含 Reason 与 Actor

```text
cancelReason != null
cancelledBy != null
cancelledAt != null
```

执行层级：

```text
Domain
```

## BI-068 CLOSED 不能 Cancel

已经关闭的 Ticket 只能保持关闭或通过 Reopen 重新处理。

执行层级：

```text
Domain
```

## BI-069 Tool Side Effect 未知时不能立即 Cancel

如果正在执行或结果未知：

```text
必须先确认 Side Effect
或进入 ESCALATED
```

执行层级：

```text
Cancellation Policy
```

## BI-070 Cancel 后必须使 Pending Action 失效

```text
pendingAction = null 或 invalidated
```

同时通知：

- Agent Runtime
- Policy / Approval
- Tool Gateway，如尚未执行

执行层级：

```text
Domain + Integration Events
```

## BI-071 Cancel 后 Active Workflow 必须终止或进入取消流程

Ticket Cancel 不能留下继续运行的 Agent Workflow。

执行层级：

```text
Application + Event
```

---

# 17. Escalation 不变量

## BI-072 Escalation 必须有 Target 与 Reason

```text
target != null
reason != null
```

执行层级：

```text
Domain
```

## BI-073 Escalation 不能丢失当前上下文

必须保留：

- Ticket History
- Agent Findings
- Tool Results
- Approval Results
- Verification Results
- Pending Risks

执行层级：

```text
Application + Timeline
```

## BI-074 Escalation 不等于失败删除

Ticket 必须继续存在并可审计。

执行层级：

```text
Domain
```

## BI-075 Escalated 状态下自动化权限受限

默认不允许新的高风险自动 Tool Action，除非人工明确授权。

执行层级：

```text
Policy + Domain
```

---

# 18. Assignment 不变量

## BI-076 当前 Assignment 最多一个

Ticket 可以：

```text
unassigned
assigned to team
assigned to team + support user
```

不能同时存在多个互斥当前负责人。

执行层级：

```text
Domain
```

## BI-077 Assignment 变更必须有 Actor 与时间

```text
assignedBy
assignedAt
```

执行层级：

```text
Value Object
```

## BI-078 Assignment History 不得覆盖

每次变更写 Append-only History。

执行层级：

```text
Application + Persistence
```

## BI-079 Assignment 不能绕过访问控制

被分配用户必须属于允许的 Support Queue 或 Role。

执行层级：

```text
Security + Application
```

---

# 19. SLA 不变量

## BI-080 每个 Ticket MVP 最多一个 Active SLA

未来支持多个 SLA 时需新设计。

执行层级：

```text
TicketSla Aggregate
```

## BI-081 SLA Deadline 不能早于 Ticket 创建时间

执行层级：

```text
TicketSla
```

## BI-082 SLA Timer 状态必须与 Ticket 状态策略一致

候选规则：

```text
WAITING_FOR_USER → PAUSED
WAITING_FOR_APPROVAL → ACTIVE 或 PAUSED，取决于 Policy
RESOLVED → MET
CANCELLED → CANCELLED
```

最终策略在 SLA 文档和状态机中冻结。

## BI-083 SLA Breach 不得无痕重置

Breach 必须保留：

```text
breachedAt
policyId
reason
```

执行层级：

```text
TicketSla
```

## BI-084 Reopen 必须定义新的 SLA 处理方式

候选：

- 继续原 SLA
- 创建新 SLA Cycle
- 使用 Reopen Policy

MVP 推荐创建新的 SLA Cycle Record，保留旧历史。

---

# 20. Idempotency 不变量

## BI-085 Create Ticket 必须支持 Idempotency-Key

相同 Requester、相同 Key 的重复请求应返回同一结果。

执行层级：

```text
API + Persistence
```

## BI-086 相同 Event 只能处理一次

唯一键：

```text
consumer_name + event_id
```

执行层级：

```text
Persistence
```

## BI-087 Idempotent Replay 必须返回稳定结果

重复处理不能：

- 再次改变状态
- 再次发送同一业务 Event
- 再次创建 Tool Action
- 再次写重复 History

执行层级：

```text
Application
```

## BI-088 相同 Command Key 不得用于不同 Payload

如果相同 Idempotency-Key 携带不同 Payload：

```text
reject as IDEMPOTENCY_KEY_REUSED
```

执行层级：

```text
API
```

---

# 21. Event Ordering 不变量

## BI-089 Event Aggregate Version 不能倒退

如果：

```text
event.aggregateVersion < ticket.version
```

通常视为 stale 或 duplicate。

执行层级：

```text
Event Consumer
```

## BI-090 Event 不能跳过必要 Version

如果当前：

```text
ticket.version = 5
```

收到：

```text
event.aggregateVersion = 8
```

必须：

```text
delay
retry
reconcile
或 DLQ
```

不能直接应用。

执行层级：

```text
Event Consumer
```

## BI-091 迟到的 Terminal Event 不能覆盖新周期

例如旧 Workflow 的 `verification.completed` 不能解决 Reopen 后的新 Workflow。

执行层级：

```text
WorkflowId + AttemptId Matching
```

---

# 22. Concurrency 不变量

## BI-092 所有 Ticket 核心更新必须使用 Expected Version

禁止无条件覆盖。

执行层级：

```text
Persistence
```

## BI-093 Optimistic Lock Conflict 后必须重新验证业务规则

不能盲目重试：

```text
reload
→ re-evaluate
→ retry or reject
```

执行层级：

```text
Application
```

## BI-094 Cancel 与 Approval Granted 竞争时只允许一个合法结果

如果 Cancel 先提交：

```text
Approval Granted 被拒绝
```

如果 Approval Granted 先提交并 Tool 尚未开始：

```text
Cancel Policy 决定是否允许取消
```

执行层级：

```text
Domain + Optimistic Lock
```

---

# 23. Transaction 不变量

## BI-095 Ticket 更新、History 和 Outbox 必须原子提交

任何一个失败：

```text
rollback all
```

执行层级：

```text
Database Transaction
```

## BI-096 Processed Event Record 与业务更新必须同事务

防止：

```text
业务已更新但 event 未标记 processed
```

或：

```text
event 已标记 processed 但业务未更新
```

执行层级：

```text
Database Transaction
```

## BI-097 数据库事务中不得调用外部系统

禁止：

- RabbitMQ Publish
- LLM
- LangSmith
- Tool Gateway
- Keycloak Admin API
- Okta / Duo

执行层级：

```text
Application Architecture
```

---

# 24. Security 与 PII 不变量

## BI-098 Employee 只能读取自己的 Ticket

除非有明确授权角色。

执行层级：

```text
Security
```

## BI-099 Auditor 只能执行只读操作

不得：

- 改状态
- 添加普通 Support Message
- 执行 Tool
- 审批

执行层级：

```text
Security
```

## BI-100 Service Identity 不能冒充 Employee

后台服务使用独立 Identity。

执行层级：

```text
Authentication
```

## BI-101 Secret 不能进入 Ticket Domain

Ticket、Message、History、Event 不得保存：

- Password
- Token
- API Key
- Private Key

执行层级：

```text
Domain + Validation
```

## BI-102 Integration Event 必须最小化 PII

`ticket.created` 不应广播完整 Description。

执行层级：

```text
Event Mapping
```

## BI-103 LangSmith Metadata 必须脱敏

允许：

```text
ticket_id
workflow_id
hashed requester id
category
status
```

禁止：

```text
raw email
access token
full login log
credential
```

执行层级：

```text
Agent Observability
```

---

# 25. Audit 与历史不变量

## BI-104 Audit Record 不得被普通业务更新覆盖

Audit 采用 Append-only。

## BI-105 Status History 不得删除

除非依法执行受控数据治理流程，并保留证明记录。

## BI-106 Actor、Reason、Time 必须随关键动作保存

关键动作包括：

- Cancel
- Reopen
- Close
- Escalate
- Assignment
- Manual Transition
- Category Override

## BI-107 Timeline 不得成为业务 Source of Truth

Timeline 是 Read Model。

真实状态来自：

```text
Ticket
Message
Approval
Tool Execution
Verification
History
```

---

# 26. Observability 不变量

## BI-108 每次 Command 和 Event 必须传播 Trace Context

至少：

```text
trace_id
correlation_id
ticket_id
workflow_id
```

## BI-109 Metrics Label 不得包含高基数字段或 PII

禁止将：

```text
ticket_id
requester_id
message_body
```

作为 Prometheus Label。

## BI-110 Telemetry Failure 不得阻止业务

OpenTelemetry 或 LangSmith Export 失败：

```text
记录本地错误
增加失败 Metric
继续业务
```

安全检查失败仍必须 Fail-closed。

---

# 27. 不变量优先级

## Critical

违反会导致安全、权限或外部 Side Effect 风险：

```text
BI-032–047
BI-048–054
BI-067–071
BI-098–103
```

## High

违反会破坏业务正确性：

```text
BI-010–022
BI-055–066
BI-085–097
```

## Medium

违反会降低可审计性和可维护性：

```text
BI-023–031
BI-072–084
BI-104–110
```

---

# 28. 不变量到实现位置映射

| 不变量类型 | 主要实现位置 |
|---|---|
| Identity / Required Fields | Value Objects / DB Constraints |
| Lifecycle | Ticket Aggregate |
| Workflow Matching | Ticket Aggregate + Event Consumer |
| Approval Matching | Ticket Aggregate + Policy Integration |
| Tool Matching | Event Consumer + Ticket Aggregate |
| Verification | TicketResolutionPolicy + Ticket Aggregate |
| Authorization | Spring Security / Application Service |
| Idempotency | Idempotency Store / Processed Events |
| Concurrency | JPA Version / Optimistic Lock |
| History | Application Transaction |
| PII | DTO Mapping / Log Redaction / Event Mapping |
| Observability | Shared OTel Instrumentation |

---

# 29. 必须覆盖的测试

每条 Critical 和 High Invariant 必须至少有一个自动化测试。

示例：

```text
shouldRejectExecutionWhenTicketIsCancelled
shouldRejectApprovalForDifferentWorkflow
shouldRejectApprovalForDifferentAction
shouldRequireVerificationBeforeResolution
shouldIgnoreDuplicateVerificationEvent
shouldRejectStaleWorkflowEventAfterReopen
shouldRollbackTicketWhenOutboxInsertFails
shouldRejectDifferentPayloadForSameIdempotencyKey
shouldRetryAfterOptimisticLockOnlyAfterReevaluation
```

---

# 30. 与状态机的关系

`03-state-machine/README_CN.md` 必须基于本文档生成。

状态机需要为每个 Transition 指明：

- 哪些 Business Invariants 适用
- 前置条件
- Actor
- Trigger
- Side Effects
- Domain Event
- Failure Code
- Idempotency Behavior

例如：

```text
WAITING_FOR_APPROVAL → EXECUTING
```

至少受以下不变量约束：

```text
BI-032
BI-033
BI-034
BI-035
BI-036
BI-038
BI-040
BI-043
```

---

# 31. 待状态机最终确认的问题

1. `REOPENED` 是否作为持久状态。
2. `FAILED` 是否为终止状态。
3. `ESCALATED` 是否允许回到 `INVESTIGATING`。
4. Tool 已经执行时 Cancel 的最终路径。
5. Approval Rejected 后回到哪个状态。
6. Approval Expired 后回到哪个状态。
7. Verification Failure 后允许多少次重试。
8. Auto-close 的时间与触发者。
9. `WAITING_FOR_APPROVAL` 是否暂停 SLA。
10. Reopen 是否创建新 SLA Cycle。

---

# 32. 验收标准

- [x] Ticket Identity 不变量已定义。
- [x] Ticket Content 不变量已定义。
- [x] Lifecycle 不变量已定义。
- [x] Active Workflow 不变量已定义。
- [x] Message 不变量已定义。
- [x] Pending Action 不变量已定义。
- [x] Approval 不变量已定义。
- [x] Tool Execution 不变量已定义。
- [x] Verification 不变量已定义。
- [x] Resolution 与 Closure 不变量已定义。
- [x] Reopen 与 Cancellation 不变量已定义。
- [x] Escalation、Assignment 和 SLA 不变量已定义。
- [x] Idempotency、Ordering 和 Concurrency 不变量已定义。
- [x] Transaction、安全、审计和可观测性不变量已定义。
- [x] 测试映射已定义。
- [ ] 最终状态转换将在 `03-state-machine/README_CN.md` 中冻结。

---

# 33. 下一步

下一份文档：

```text
03-state-machine/README_CN.md
03-state-machine/README_EN.md
```

状态机必须引用本文档中的 `BI-xxx` 编号。
