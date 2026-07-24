# OpsMind Ticket Workflow — 10 Error Handling and Reconciliation

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Error Handling, Recovery and Reconciliation Design  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `03-state-machine/README_CN.md`、`04-use-cases/README_CN.md`、`05-api-contracts/README_CN.md`、`06-event-contracts/README_CN.md`、`07-data-model/README_CN.md`、`08-transaction-and-outbox/README_CN.md`、`09-concurrency-and-idempotency/README_CN.md`  
> **建议路径：** `docs/low-level-design/domains/02-ticket-workflow/10-failure-handling/README_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow 对业务错误、并发冲突、依赖故障、消息异常、数据不一致和安全事件的统一处理方式。

本文档冻结：

- Error Taxonomy
- Error Code 命名与结构
- Retryable / Non-retryable 判定
- Domain、Application、Infrastructure Error 映射
- HTTP Error Response
- RabbitMQ ACK / Retry / DLQ 决策
- 自动恢复
- Reconciliation Case
- DLQ Triage
- Manual Recovery
- Compensating Action
- User-visible 与 Internal Error 分离
- Audit 与 Operator 权限
- Observability、Metrics、Alert
- Runbook 与故障演练
- Error Handling Test Strategy

核心目标：

```text
同一类故障必须得到一致处理。
不可恢复错误不能被无限重试。
可恢复错误不能因为一次失败而永久丢失。
未知 Side Effect 不能被静默重试或隐藏。
人工恢复不能绕过状态机、审批、幂等和审计。
```

---

# 2. 设计原则

## 2.1 Error 是结构化业务对象，不是自由文本

所有可预期错误必须有稳定：

```text
errorCode
category
retryability
severity
audience
source
```

禁止仅依赖：

```text
exception.getMessage()
```

## 2.2 用户错误与内部错误分离

用户只看到：

- 可理解的 Message
- 可执行的下一步
- Trace / Correlation Reference

用户不可看到：

- Stack Trace
- SQL
- Internal Hostname
- RabbitMQ Queue Name
- Raw Dependency Response
- Secret
- Prompt
- Internal Policy Detail

## 2.3 Fail Closed

如果系统无法确认以下事实：

- Approval 是否有效
- Tool Side Effect 是否发生
- Verification 是否属于当前 Attempt
- Actor 是否有权限
- Event 是否被篡改
- Payload 是否包含 Secret

系统必须拒绝高风险继续执行。

## 2.4 Retry 不是默认行为

任何 Retry 必须回答：

```text
这个操作是否幂等？
上一次是否可能已产生 Side Effect？
当前业务状态是否仍允许？
Retry 是否使用相同 Identity？
Retry Budget 是否还有余额？
```

## 2.5 Reconciliation 不是通用“修数据”

Reconciliation 必须：

- 重新读取 Source of Truth
- 保留原始证据
- 生成明确 Decision
- 通过合法 Use Case 修复
- 记录 Operator、Reason 和 Before/After
- 不直接手工修改 Ticket Status

## 2.6 Compensation 是新业务动作

Compensating Action 不是数据库回滚。

它是：

```text
一个新的、显式、可审计、可审批的业务动作
```

---

# 3. Error Taxonomy

| Category | 说明 | 默认 Retry |
|---|---|---:|
| `VALIDATION` | Request / Event Schema 不合法 | no |
| `AUTHENTICATION` | 身份缺失或 Token 无效 | no |
| `AUTHORIZATION` | Actor 无权限 | no |
| `BUSINESS_RULE` | Business Invariant 不满足 | no |
| `STATE_CONFLICT` | 当前状态不允许操作 | no |
| `CONCURRENCY` | Version、Lock、并发冲突 | conditional |
| `IDEMPOTENCY` | Key、EventId 或 Payload 冲突 | conditional |
| `REFERENCE` | Workflow、Action、Approval 等引用错误 | conditional |
| `ORDERING` | Event 前置事实尚未到达 | yes, bounded |
| `DEPENDENCY_TRANSIENT` | 暂时性依赖故障 | yes |
| `DEPENDENCY_PERMANENT` | 永久性依赖错误 | no |
| `MESSAGING` | Publish、Confirm、Routing、DLQ 问题 | conditional |
| `DATA_INTEGRITY` | Snapshot、History、Constraint 不一致 | no automatic |
| `SECURITY` | Secret、篡改、越权、可疑引用 | no |
| `RESOURCE_EXHAUSTION` | Rate Limit、Pool、Disk、Memory | conditional |
| `UNKNOWN` | 未分类异常 | no automatic by default |

---

# 4. Error Severity

| Severity | 含义 | 示例 |
|---|---|---|
| `INFO` | 预期业务拒绝 | Invalid State、Expired Reopen |
| `WARNING` | 可恢复或需要关注 | Transient DB、Out-of-order |
| `ERROR` | 单个 Ticket 处理失败 | Dependency Failure、DLQ |
| `CRITICAL` | 可能影响数据完整性或安全 | EventId Payload Conflict、Secret Leak |
| `FATAL` | 服务无法安全继续 | Migration Corruption、Schema Ownership 破坏 |

Severity 不决定 Retry；Retryability 必须独立定义。

---

# 5. Retryability

```text
NOT_RETRYABLE
RETRY_IMMEDIATE
RETRY_WITH_BACKOFF
RETRY_AFTER_RECONCILIATION
MANUAL_ONLY
```

## 5.1 `NOT_RETRYABLE`

示例：

- Validation Error
- Unauthorized
- Invalid State
- Reopen Window Expired
- Event Schema Invalid
- Terminal Result Conflict

## 5.2 `RETRY_IMMEDIATE`

仅用于短暂且无 Side Effect 风险的内部错误：

- Deadlock
- Optimistic Conflict，重新验证后仍合法
- Temporary Connection Reset

最多：

```text
3 次
```

## 5.3 `RETRY_WITH_BACKOFF`

示例：

- RabbitMQ unavailable
- Database temporarily unavailable
- Out-of-order Event
- Dependency rate limited
- Publisher Confirm Timeout

## 5.4 `RETRY_AFTER_RECONCILIATION`

示例：

- Tool Result Unknown
- Commit 结果无法确认
- Stale Idempotency IN_PROGRESS
- Conflicting external terminal result

## 5.5 `MANUAL_ONLY`

示例：

- Data Integrity Violation
- Security Alert
- Secret in Event
- Cross-Ticket Reference Corruption
- Compensation requires approval

---

# 6. Canonical Error Descriptor

```text
ErrorDescriptor
├── code
├── category
├── severity
├── retryability
├── sourceLayer
├── userMessageKey
├── operatorMessage
├── httpStatus?
├── brokerDisposition?
├── alertPolicy
├── auditRequired
└── safeDetails
```

示例：

```json
{
  "code": "TOOL_RESULT_UNKNOWN",
  "category": "REFERENCE",
  "severity": "ERROR",
  "retryability": "RETRY_AFTER_RECONCILIATION",
  "sourceLayer": "INTEGRATION",
  "userMessageKey": "ticket.processing_requires_review",
  "operatorMessage": "Tool execution result could not be confirmed.",
  "httpStatus": 202,
  "brokerDisposition": "ACK_AND_OPEN_RECONCILIATION",
  "alertPolicy": "WARNING",
  "auditRequired": true
}
```

---

# 7. Error Code 命名规则

格式：

```text
<DOMAIN>_<SUBJECT>_<CONDITION>
```

Ticket Domain 内可省略 `TICKET_`，但对外 Contract 推荐保留稳定短码。

示例：

```text
TICKET_NOT_FOUND
INVALID_STATE_TRANSITION
CONCURRENT_UPDATE
IDEMPOTENCY_KEY_REUSED
REQUEST_IN_PROGRESS
WORKFLOW_REFERENCE_MISMATCH
APPROVAL_REFERENCE_MISMATCH
TOOL_RESULT_UNKNOWN
TOOL_TERMINAL_RESULT_CONFLICT
VERIFICATION_TERMINAL_RESULT_CONFLICT
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
OUTBOX_PUBLISH_RETRY_EXHAUSTED
DATA_INTEGRITY_CONFLICT
```

禁止：

```text
ERR_01
UNKNOWN_2
FAILED
BAD_REQUEST_ABC
```

---

# 8. Error Source Layer

```text
DOMAIN
APPLICATION
API
PERSISTENCE
MESSAGING
INTEGRATION
SECURITY
SCHEDULER
RECONCILIATION
```

每个 Layer 只负责自己的错误语义。

例如：

- Domain：`INVALID_STATE_TRANSITION`
- Persistence：`CONCURRENT_UPDATE`
- Messaging：`UNROUTABLE_MESSAGE`
- Security：`EVENT_SECRET_DETECTED`

---

# 9. Domain Error

Domain Error 表示输入在当前 Aggregate 业务状态下不合法。

推荐：

```text
DomainError
├── code
├── invariantId
├── currentState
├── safeContext
└── occurredAt
```

示例：

```text
INVALID_STATE_TRANSITION
CANCELLATION_NOT_ALLOWED
VERIFICATION_REQUIRED
REOPEN_WINDOW_EXPIRED
ACTIVE_WORKFLOW_ALREADY_EXISTS
```

Domain 不决定 HTTP Status 或 RabbitMQ Disposition。

---

# 10. Application Error

Application Layer 负责将 Domain、Persistence、Integration Error 转换为 Use Case Result。

```text
ApplicationError
├── errorDescriptor
├── useCaseId
├── ticketId
├── commandId?
├── eventId?
├── retryAfter?
└── causeReference?
```

Application Layer 决定：

- 是否 Rollback
- 是否记录 Processed Event
- 是否写 Reconciliation Case
- 是否发布 Escalation Event
- 对 API 返回什么结果

---

# 11. Infrastructure Error

基础设施异常必须转换为稳定类型：

```text
DatabaseUnavailable
DeadlockDetected
OptimisticLockConflict
OutboxInsertFailed
RabbitPublishFailed
PublisherConfirmTimeout
UnroutableMessage
SchemaValidationFailed
DependencyTimeout
DependencyRateLimited
```

禁止让 Driver Exception 或 Vendor Error 直接穿透到 Controller / Consumer。

---

# 12. Spring Exception Mapping

建议层次：

```text
TicketDomainException
TicketApplicationException
TicketInfrastructureException
TicketSecurityException
```

API 使用：

```text
@RestControllerAdvice
```

统一映射为 Error Envelope。

Consumer 使用：

```text
EventProcessingDecision
```

而不是把所有 Exception 抛给 Container 盲目重试。

---

# 13. HTTP Error Mapping

| Error Code | HTTP |
|---|---:|
| `VALIDATION_ERROR` | 400 |
| `UNAUTHENTICATED` | 401 |
| `FORBIDDEN` | 403 |
| `TICKET_NOT_FOUND` | 404 |
| `IDEMPOTENCY_KEY_REUSED` | 409 |
| `REQUEST_IN_PROGRESS` | 409 |
| `DATA_INTEGRITY_CONFLICT` | 409 / 500 |
| `CONCURRENT_UPDATE` | 412 |
| `INVALID_STATE_TRANSITION` | 422 |
| `CANCELLATION_NOT_ALLOWED` | 422 |
| `REOPEN_WINDOW_EXPIRED` | 422 |
| `RATE_LIMITED` | 429 |
| `DEPENDENCY_UNAVAILABLE` | 503 |
| `INTERNAL_ERROR` | 500 |

---

# 14. User-visible Error Envelope

```json
{
  "error": {
    "code": "CANCELLATION_NOT_ALLOWED",
    "message": "This ticket cannot be cancelled while an action is being executed.",
    "traceId": "8f03d65a...",
    "correlationId": "INC-2048",
    "retryable": false,
    "nextAction": "Wait for the current action to finish or contact IT support."
  }
}
```

用户可见字段：

```text
code
message
traceId
correlationId
retryable
retryAfterSeconds?
nextAction?
```

禁止字段：

```text
exceptionClass
stackTrace
sqlState
queueName
rawDependencyResponse
internalPolicyRule
secret
```

---

# 15. User Message Key

后端 Error Descriptor 使用稳定 Key：

```text
ticket.not_found
ticket.concurrent_update
ticket.cancellation_not_allowed
ticket.reopen_window_expired
ticket.processing_temporarily_unavailable
ticket.processing_requires_review
ticket.request_in_progress
```

Frontend 根据 Locale 翻译。

后端仍提供安全默认英文 Message。

---

# 16. User-visible vs Internal Mapping

| Internal Error | User Message |
|---|---|
| `DB_CONNECTION_FAILURE` | Service temporarily unavailable |
| `RABBIT_CONFIRM_TIMEOUT` | Request accepted; processing may be delayed |
| `TOOL_RESULT_UNKNOWN` | Ticket requires IT review |
| `EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD` | Generic internal error |
| `INVALID_STATE_TRANSITION` | Action is not available in current status |
| `STALE_EVENT` | 不向用户显示 |
| `OUT_OF_ORDER_EVENT` | 不向用户显示 |
| `DLQ_REPLAY_FAILED` | Generic internal error |

---

# 17. API Failure Rules

## 17.1 Commit 前失败

- Rollback
- 返回对应 Error
- 不写成功 Outbox
- Idempotency Record 根据类型回滚或标记失败

## 17.2 Commit 后 Response 丢失

- 客户端用同 Idempotency-Key 重试
- 返回 Stored Response
- 不创建第二次业务效果

## 17.3 Broker 不可用

如果业务事务和 Outbox 已提交：

- API Command 仍可返回成功
- Outbox Publisher 后续重试
- 可返回 Processing Status

---

# 18. Event Processing Decision

```text
EventProcessingDecision
├── classification
├── brokerDisposition
├── processedEventResult
├── retryDelay
├── reconciliationRequired
├── alertSeverity
└── errorCode
```

`brokerDisposition`：

```text
ACK
RETRY
DLQ
ACK_AND_RECONCILE
```

---

# 19. Broker Decision Matrix

| Classification | Disposition |
|---|---|
| `APPLY` | Commit then ACK |
| `DUPLICATE` | ACK |
| `BUSINESS_DUPLICATE` | Record then ACK |
| `STALE` | Record then ACK |
| `REJECTED_BUSINESS_RULE` | ACK，安全敏感时 DLQ |
| `OUT_OF_ORDER` | Retry |
| `TRANSIENT_FAILURE` | Retry |
| `TOOL_RESULT_UNKNOWN` | ACK_AND_RECONCILE |
| `CORRUPT_REFERENCE` | DLQ |
| `EVENT_ID_REUSE` | DLQ |
| `TERMINAL_RESULT_CONFLICT` | DLQ |
| `SECRET_DETECTED` | DLQ |
| `UNKNOWN_EXCEPTION` | Limited Retry，之后 DLQ |

---

# 20. Retry Policy

## Immediate Retry

```text
maxAttempts = 3
backoff = 10ms–100ms jitter
```

适用：

- Deadlock
- Optimistic Lock
- Temporary Connection Reset

## Broker Retry

```text
5s
30s
5m
```

之后 DLQ。

## Dependency Retry

必须考虑：

- Idempotency
- Timeout 发生阶段
- Side Effect 是否可能发生
- `Retry-After`
- Circuit Breaker

## Retry Budget

Retry Budget 按：

```text
operationType
dependency
ticketId
workflowId
actionId
```

追踪。

超过 Budget：

```text
Open Reconciliation
或
Escalate
```

---

# 21. Circuit Breaker

对外部同步依赖可使用 Circuit Breaker：

```text
CLOSED
OPEN
HALF_OPEN
```

Ticket Service 自身不在事务内调用外部 Tool，但可能调用：

- Identity Context
- Attachment Metadata
- Workflow Provisioning API

Circuit Open 时：

- 不继续发送新同步请求
- 返回 Temporary Error 或转异步
- 不改变已提交 Ticket 状态
- 记录 Dependency Alert

Circuit Breaker 不替代 Retry Budget。

---

# 22. Timeout Policy

每类调用必须有明确 Timeout：

| 调用 | 建议 |
|---|---:|
| PostgreSQL Command Transaction | 3s |
| Event Consumer Transaction | 5s |
| Internal Read API | 2s |
| Workflow Provisioning | 3s |
| RabbitMQ Confirm | 5s |
| Reconciliation External Read | 5s |
| Operator Recovery Command | 10s |

Timeout 不是证据表明远程操作没有发生。

---

# 23. Reconciliation 定义

Reconciliation 是对不确定、冲突或部分失败状态进行事实核对，并通过合法业务动作恢复一致性的流程。

典型触发：

- Tool Result Unknown
- Conflicting Tool Result
- Conflicting Verification Result
- Long-lived Out-of-order Event
- Outbox Publish Retry Exhausted
- Stale Idempotency IN_PROGRESS
- Snapshot 与 History 不一致
- Missing Current Resolution Cycle
- DLQ Message 需要人工决策

---

# 24. Reconciliation Case

建议逻辑模型：

```text
ReconciliationCase
├── reconciliationId
├── ticketId
├── resolutionCycleId
├── type
├── status
├── severity
├── sourceErrorCode
├── sourceEventId?
├── sourceCommandId?
├── evidenceReferences
├── proposedDecision?
├── finalDecision?
├── assignedTeam?
├── assignedOperator?
├── createdAt
├── updatedAt
├── resolvedAt?
└── version
```

---

# 25. Reconciliation Type

```text
TOOL_RESULT_UNKNOWN
TOOL_TERMINAL_RESULT_CONFLICT
VERIFICATION_TERMINAL_RESULT_CONFLICT
APPROVAL_TERMINAL_RESULT_CONFLICT
OUT_OF_ORDER_EVENT
OUTBOX_PUBLISH_FAILURE
IDEMPOTENCY_IN_PROGRESS_STALE
DATA_INTEGRITY_MISMATCH
CROSS_REFERENCE_CORRUPTION
DLQ_REVIEW
SECURITY_REVIEW
```

---

# 26. Reconciliation Status

```text
OPEN
INVESTIGATING
WAITING_FOR_EXTERNAL_FACT
WAITING_FOR_APPROVAL
RECOVERY_READY
RECOVERY_EXECUTING
RESOLVED
DISMISSED
FAILED
```

状态只能通过专用 Reconciliation Use Case 改变。

---

# 27. Reconciliation Outcome

```text
NO_ACTION
MARK_EVENT_STALE
REPLAY_ORIGINAL_EVENT
CREATE_CORRECTION_EVENT
REAPPLY_SAFE_TRANSITION
ESCALATE_TICKET
REQUEST_NEW_APPROVAL
EXECUTE_COMPENSATION
REBUILD_IDEMPOTENCY_RESPONSE
REPAIR_DERIVED_RECORD
MANUAL_REVIEW_REQUIRED
SECURITY_INCIDENT
```

---

# 28. Reconciliation Workflow

```text
1. Create Case
2. Freeze unsafe automation when required
3. Collect immutable evidence
4. Read authoritative external state
5. Compare Ticket snapshot and history
6. Determine current business truth
7. Propose recovery action
8. Require approval if risk threshold demands
9. Execute through normal Use Case / Event path
10. Verify recovery result
11. Record final decision
12. Resolve Case
```

---

# 29. Evidence Rules

允许 Evidence Reference：

```text
eventId
commandId
workflowId
actionId
approvalId
toolExecutionId
verificationId
historyId
outboxId
traceId
externalAuditReference
```

不得将不可验证的自由文本作为唯一依据。

Evidence 必须：

- Immutable
- Timestamped
- Source Identified
- Redacted
- Access Controlled

---

# 30. Tool Result Unknown Reconciliation

## Trigger

```text
tool.execution.result_unknown
```

## Initial Action

```text
Ticket → ESCALATED
automationRestricted = true
open Reconciliation Case
```

## Investigation

1. 查询 Tool Gateway Execution Record。
2. 查询目标系统 Audit Log。
3. 检查 Side Effect 是否发生。
4. 检查是否可安全 Verification。
5. 不自动重复执行原 Tool。

## Outcomes

### Confirmed Applied

```text
start Verification
```

不能直接 Resolve。

### Confirmed Not Applied

```text
return to INVESTIGATING
```

如需重新执行，必须创建新 Action / Execution Attempt。

### Still Unknown

```text
MANUAL_REVIEW_REQUIRED
```

---

# 31. Tool Terminal Result Conflict

同一：

```text
toolExecutionId + executionAttemptId
```

同时出现 Success 与 Failure。

处理：

```text
DLQ conflicting event
Open Critical Reconciliation
Freeze automation
Query Tool Gateway source of truth
Do not silently choose last event
```

恢复后使用：

```text
Correction Event
```

而不是修改原 Event。

---

# 32. Verification Conflict Reconciliation

同一 VerificationId 出现冲突结果：

1. Freeze auto-resolution。
2. Open Critical Case。
3. 查询 Verification Evidence。
4. 检查 Test Run Identity。
5. 检查 Evidence 是否属于当前 Attempt。
6. 需要时创建新 VerificationId 重跑。
7. 只有新的可信 Verification Success 可以 Resolve。

---

# 33. Approval Conflict Reconciliation

同一 ApprovalId 出现：

```text
GRANTED
REJECTED
```

或：

```text
GRANTED
EXPIRED
```

处理：

- 停止 Tool Execution，若尚未开始。
- 如果 Tool 已开始，进入 Tool Result Reconciliation。
- 查询 Approval Domain Source of Truth。
- 保存 Approver Audit Reference。
- 通过 Correction Event 发布最终事实。
- 不修改原审批事件。

---

# 34. Out-of-order Reconciliation

Event 经 5s、30s、5m Retry 后仍缺少前置事实：

1. 查询 Producer 的业务记录。
2. 查询 RabbitMQ / Event Archive 是否存在前置 Event。
3. 查询 Ticket Processed Event Store。
4. 判断前置 Event：
   - 丢失
   - 未发布
   - 已处理但本地记录异常
   - 业务上从未发生
5. 根据结果：
   - Replay 前置 Event
   - 创建 Correction Event
   - Mark 当前 Event Stale
   - DLQ / Manual Review

---

# 35. Idempotency Reconciliation

Stale `IN_PROGRESS`：

- 查 Resource
- 查 History
- 查 Outbox
- 查 Stored Response

结果：

### Business Commit 已发生

```text
Rebuild response
Mark COMPLETED
```

### 未发生

```text
Mark FAILED_RETRYABLE
Allow same-key retry
```

### 无法判断

```text
Keep blocked
Open Case
```

不能直接删除记录后重新执行。

---

# 36. Data Integrity Reconciliation

典型检查：

```text
Ticket 没有 Current Resolution Cycle
Status History Version 不连续
WAITING_FOR_APPROVAL 没有 Active Pending Action
WAITING_FOR_USER 没有 Open Request
RESOLVED 没有 Verification
CLOSED 没有 Resolution
Outbox Aggregate Version 不匹配
```

恢复原则：

- 优先修复 Derived / Infrastructure Record。
- 不直接推测并修改业务状态。
- 如果 Source of Truth 冲突，人工审核。
- 所有修复使用 Migration / Recovery Command。
- 记录 Before / After Snapshot Hash。

---

# 37. DLQ Triage

## 37.1 DLQ Message Metadata

至少保留：

```text
eventId
eventType
eventVersion
routingKey
producer
ticketId
workflowId
payloadHash
firstFailedAt
lastFailedAt
retryCount
lastErrorCode
traceId
originalHeaders
```

## 37.2 Triage Priority

| Priority | 条件 |
|---|---|
| P0 | Secret、EventId Payload Conflict、Cross-Ticket Corruption |
| P1 | Tool / Verification Terminal Conflict |
| P2 | Persistent Out-of-order、Data Integrity |
| P3 | Invalid Schema、Unknown Version |
| P4 | 已知无害 Stale Event 误入 DLQ |

---

# 38. DLQ Triage Workflow

```text
NEW
→ CLASSIFIED
→ INVESTIGATING
→ READY_TO_REPLAY
→ REPLAYED
→ VERIFIED
→ RESOLVED
```

Alternative：

```text
DISMISSED
SECURITY_INCIDENT
MANUAL_RECOVERY_REQUIRED
```

---

# 39. Replay Rules

## Original Event Replay

保留：

```text
same eventId
same payload
same occurredAt
```

增加：

```text
replayed = true
replayOperator
replayTime
reconciliationId
```

适用：

- 原 Event 合法
- 失败原因已修复
- 重放不会产生重复 Side Effect

## Correction Event

Payload 需要更改时：

```text
new eventId
causationId = original eventId
correctionOfEventId = original eventId
```

原 Event 不可修改。

---

# 40. Manual Recovery

人工恢复必须使用专用命令，例如：

```text
MarkEventStaleCommand
ReplayEventCommand
CreateCorrectionEventCommand
ResumeInvestigationCommand
StartNewVerificationCommand
RequestNewApprovalCommand
ExecuteCompensationCommand
ResolveReconciliationCommand
```

禁止：

```sql
UPDATE ticket.tickets SET status = ...
```

---

# 41. Recovery Authorization

| 操作 | 最低权限 |
|---|---|
| 查看 Reconciliation | IT_SUPPORT / AUDITOR |
| Replay 普通 Event | IT_ADMIN |
| Correction Event | IT_ADMIN + Domain Owner |
| Compensation | IT_ADMIN + Approval |
| Security Case | SECURITY_ADMIN |
| Data Repair | DB_ADMIN + Application Owner |
| Resolve Case | Assigned Operator / Manager |

高风险操作推荐 Four-eyes：

```text
一个 Operator 提议
另一个 Approver 批准
```

---

# 42. Recovery Audit

每次人工恢复记录：

```text
operatorId
approverId?
reconciliationId
ticketId
actionType
reasonCode
beforeSnapshotHash
afterSnapshotHash
sourceEvidence
commandId
occurredAt
result
```

审计记录 Append-only。

---

# 43. Compensating Action

## 43.1 定义

用于撤销、抵消或修复已发生的外部 Side Effect。

示例：

- 恢复被错误移除的权限
- 恢复错误修改的设备配置
- 重新启用被误禁用的账号
- 撤销错误分配的许可证

## 43.2 规则

- 必须是 Tool Catalog 中明确支持的 Action。
- 必须有自己的 `actionId`。
- 必须重新执行 Policy Evaluation。
- 根据风险重新 Approval。
- 必须独立 Verification。
- 不能复用原 `toolExecutionId`。
- 必须引用 `compensatesActionId`。

## 43.3 不支持自动 Compensation 的情况

- 不可逆外部操作
- 数据已被永久删除
- 安全影响未知
- 目标系统状态不确定
- Compensation 可能扩大影响

这类情况必须人工处理。

---

# 44. Compensation State Flow

```text
ESCALATED
→ INVESTIGATING
→ WAITING_FOR_APPROVAL
→ EXECUTING
→ VERIFYING
→ RESOLVED
```

Compensation 不能绕过正常 Ticket State Machine。

---

# 45. Partial Failure

## 45.1 Ticket Commit 成功，Notification 失败

Ticket 状态保持。

Notification Consumer 自己 Retry / DLQ。

## 45.2 Ticket Resolve 成功，Memory 写入失败

Ticket 保持 RESOLVED。

Memory Consumer 重试。

不能回滚 Ticket Resolution。

## 45.3 Tool 执行成功，Verification 启动失败

Ticket 不应 Resolve。

根据错误：

```text
VERIFYING
或
FAILED / ESCALATED
```

恢复后重新启动 Verification，不重复执行 Tool。

## 45.4 Approval 成功，Tool Gateway 不可用

Ticket 可以保持 EXECUTING，但必须有明确 Execution Dispatch 状态和 Timeout 监控。

若长期失败：

```text
FAILED
或
ESCALATED
```

不能重复创建 Action。

---

# 46. Error Budget 与 Escalation

每个自动化阶段有失败上限：

| 阶段 | Budget |
|---|---:|
| Optimistic Conflict | 3 |
| DB Transient Retry | 3 |
| Consumer Immediate Retry | 3 |
| Broker Retry Queue | 3 levels |
| Outbox Publish Attempt | 10 |
| Verification Failure | 2，第三次 Escalate |
| Workflow Automation Retry | 按 Policy，MVP 建议 2 |
| Reconciliation Automatic Attempt | 1–3，按类型 |

超过 Budget：

```text
FAILED
ESCALATED
DLQ
或
Manual Review
```

不得无限循环。

---

# 47. Unknown Error

未分类 Exception：

1. 生成 `INTERNAL_ERROR`。
2. Rollback 当前事务。
3. 记录 Error Fingerprint。
4. 不返回内部 Detail。
5. Event Consumer 有限 Retry。
6. 重复达到阈值后 DLQ。
7. 创建 Alert。
8. 由工程团队补充 Error Taxonomy。

Unknown Error 默认不自动执行高风险恢复。

---

# 48. Error Fingerprint

用于聚合同类异常：

```text
SHA-256(
  exception family
  + application operation
  + top normalized stack frames
  + dependency
  + error code
)
```

Fingerprint 不包含：

- TicketId
- RequesterId
- Secret
- 动态 Message Body

---

# 49. Observability

## Spans

```text
ticket.error.handle
ticket.retry.execute
ticket.reconciliation.open
ticket.reconciliation.investigate
ticket.reconciliation.recover
ticket.dlq.triage
ticket.compensation.execute
```

## Trace Attributes

```text
opsmind.error_code
opsmind.error_category
opsmind.severity
opsmind.retryability
opsmind.retry_count
opsmind.reconciliation_id
opsmind.recovery_outcome
opsmind.broker_disposition
```

---

# 50. Metrics

```text
ticket_error_total
ticket_error_retryable_total
ticket_error_non_retryable_total
ticket_retry_attempt_total
ticket_retry_exhausted_total
ticket_reconciliation_open_total
ticket_reconciliation_resolved_total
ticket_reconciliation_failed_total
ticket_reconciliation_age_seconds
ticket_dlq_message_total
ticket_dlq_replay_total
ticket_dlq_replay_failed_total
ticket_compensation_requested_total
ticket_compensation_completed_total
ticket_compensation_failed_total
ticket_unknown_error_total
```

允许 Label：

```text
error_code
category
severity
operation
event_type
outcome
```

禁止：

```text
ticket_id
event_id
workflow_id
operator_id
trace_id
```

---

# 51. Alert

## Critical

```text
SECURITY category error > 0
EventId Payload Conflict > 0
Cross-Ticket Reference Corruption > 0
Tool / Verification Terminal Conflict > 0
Reconciliation P0 open > threshold
Outbox oldest unpublished > 5 minutes
```

## Warning

```text
Retry exhausted rate rising
DLQ backlog rising
Reconciliation age > SLA
Unknown error fingerprint spike
Dependency transient error rate > threshold
```

---

# 52. Reconciliation SLA

| Priority | Acknowledge | Resolve Target |
|---|---:|---:|
| P0 | 15 min | 4 h |
| P1 | 30 min | 8 h |
| P2 | 4 h | 2 business days |
| P3 | 1 business day | 5 business days |
| P4 | 2 business days | Best effort |

这是 Operational SLA，不等于用户 Ticket SLA。

---

# 53. Operator Runbook

每个 Error Code Runbook 至少包含：

```text
Meaning
User Impact
Automatic Behavior
Data to Inspect
Safe Queries
Unsafe Actions
Replay Eligibility
Compensation Eligibility
Escalation Team
Verification Steps
Closure Criteria
```

---

# 54. Safe Diagnostic Queries

允许只读查询：

```text
Ticket Snapshot
Status History
Current Resolution Cycle
Pending Action
Processed Event
Outbox Event
Reconciliation Case
External Reference Status
```

禁止在生产直接运行未经审核的写 SQL。

---

# 55. Testing Strategy

## Unit Tests

```text
shouldMapDomainErrorToStableDescriptor
shouldNotRetryValidationError
shouldRetryDeadlockWithBoundedAttempts
shouldFailClosedOnUnknownApprovalStatus
shouldMapInternalErrorToSafeUserMessage
shouldOpenReconciliationForUnknownToolResult
shouldRequireCorrectionEventForChangedPayload
shouldRequireNewApprovalForCompensation
```

## Integration Tests

```text
shouldRollbackOnOutboxInsertFailure
shouldAckStaleEventWithoutStateChange
shouldRetryOutOfOrderEventThenOpenReconciliation
shouldDlqEventIdPayloadConflict
shouldRebuildIdempotencyResponseAfterCrash
shouldReplayOriginalEventWithSameEventId
shouldPublishCorrectionWithNewEventId
shouldPreventManualStatusMutation
shouldAuditManualRecovery
```

## Chaos Tests

```text
brokerDownDuringPublish
databaseRestartDuringConsumerTransaction
toolTimeoutAfterRequestSent
verificationServiceReturnsConflictingResults
approvalEventsArriveInConflictingOrder
outboxPublisherFailsForExtendedPeriod
```

---

# 56. Error Injection

测试环境提供受控 Fault Injection：

```text
FAIL_OUTBOX_INSERT
FAIL_AFTER_TICKET_UPDATE
FAIL_BEFORE_TRANSACTION_COMMIT
FAIL_AFTER_COMMIT_BEFORE_HTTP_RESPONSE
FAIL_AFTER_CONSUMER_COMMIT_BEFORE_ACK
FAIL_BEFORE_PUBLISH_CONFIRM
RETURN_UNROUTABLE_MESSAGE
RETURN_TOOL_RESULT_UNKNOWN
RETURN_CONFLICTING_VERIFICATION
```

Fault Injection 必须：

- 仅在 local / ci / demo 可启用
- 需要明确 Feature Flag
- 不能在 prod 默认开放

---

# 57. Acceptance Criteria

- [x] Error Taxonomy 已定义。
- [x] Severity 与 Retryability 已定义。
- [x] Canonical Error Descriptor 已定义。
- [x] Error Code 命名规则已定义。
- [x] Domain、Application、Infrastructure Error 已分层。
- [x] HTTP 与 User-visible Error 已定义。
- [x] Broker ACK / Retry / DLQ Matrix 已定义。
- [x] Retry、Circuit Breaker 与 Timeout 已定义。
- [x] Reconciliation Model、Type、Status 和 Outcome 已定义。
- [x] Tool、Verification、Approval、Out-of-order、Idempotency 和 Data Integrity Reconciliation 已定义。
- [x] DLQ Triage 与 Replay 已定义。
- [x] Manual Recovery、Authorization 和 Audit 已定义。
- [x] Compensation 规则已定义。
- [x] Partial Failure 与 Error Budget 已定义。
- [x] Observability、Alert、Runbook 和测试要求已定义。

---

# 58. 对 Data Model 的增量要求

后续更新 `07-data-model` 时建议增加：

```text
ticket.reconciliation_cases
ticket.reconciliation_evidence
ticket.recovery_audit_records
```

如果 MVP 时间有限，可以先将 Reconciliation Case 作为 Support Operations 模块的轻量表实现，但不能只依赖日志或 Slack 消息。

---

# 59. 下一步

下一份文档建议：

```text
11-security/README_CN.md
11-security/README_EN.md
```

该文档将定义：

- Keycloak Role / Scope
- Resource Ownership
- Queue-based Access
- Internal Service Identity
- Approval Trust Boundary
- PII Redaction
- Secret Handling
- Audit Authorization
- Threat Model 与 Abuse Cases
