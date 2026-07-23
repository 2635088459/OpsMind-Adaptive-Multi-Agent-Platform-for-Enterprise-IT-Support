# OpsMind Ticket Workflow — 04 Use Cases

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Use Case Specification  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `01-domain-model_CN.md`、`02-business-invariants_CN.md`、`03-state-machine_CN.md`  
> **建议路径：** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/04-use-cases_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow MVP 的主要 Use Case。

每个 Use Case 说明：

- Actor
- Trigger
- Command / Query
- Preconditions
- Main Flow
- Alternative Flow
- Failure Flow
- State Transition
- Applicable Business Invariants
- Transaction Boundary
- Domain Event
- Integration Event
- Idempotency
- Authorization
- Observability
- Expected Result

本文档是后续以下设计的直接输入：

```text
05-api-contracts
06-event-contracts
07-data-model
08-transaction-and-outbox
09-concurrency-and-idempotency
13-package-and-class-design
14-testing-strategy
```

---

# 2. Use Case 编号

```text
UC-01 Create Ticket
UC-02 Get Ticket
UC-03 List Requester Tickets
UC-04 List Support Queue Tickets
UC-05 Add Ticket Message
UC-06 Start Triage
UC-07 Complete Classification
UC-08 Request More Information
UC-09 Receive User Reply
UC-10 Associate Active Workflow
UC-11 Request Approval
UC-12 Handle Approval Granted
UC-13 Handle Approval Rejected
UC-14 Handle Approval Expired
UC-15 Start Auto-approved Tool Execution
UC-16 Handle Tool Execution Success
UC-17 Handle Tool Execution Failure
UC-18 Handle Unknown Tool Result
UC-19 Start Verification
UC-20 Handle Verification Success
UC-21 Handle Verification Failure
UC-22 Resolve Ticket
UC-23 Confirm and Close Ticket
UC-24 Auto-close Ticket
UC-25 Reopen Ticket
UC-26 Cancel Ticket
UC-27 Escalate Ticket
UC-28 Assign Ticket
UC-29 Retry Failed Automation
UC-30 Retrieve Ticket Timeline
```

---

# 3. 通用执行模板

所有 Command Use Case 统一遵循：

```text
1. Authenticate actor or validate service identity
2. Authorize operation
3. Validate request schema
4. Validate idempotency key or event ID
5. Load required aggregate
6. Validate expected version
7. Apply domain behavior
8. Persist aggregate changes
9. Insert history / append-only records
10. Insert outbox event
11. Mark inbound event processed, when applicable
12. Commit
13. Return response
14. Publish asynchronously through Outbox Publisher
```

Query Use Case 不修改业务状态。

---

# 4. UC-01 Create Ticket

## Actor

```text
EMPLOYEE
IT_SUPPORT
AUTHORIZED_SERVICE
```

## Trigger

用户在 Web Portal 提交 IT 问题。

## Command

```text
CreateTicketCommand
├── requesterId
├── title
├── description
├── applicationCode
├── source
└── idempotencyKey
```

## Preconditions

- Actor 已认证。
- RequesterId 合法。
- Title、Description 和 ApplicationCode 通过验证。
- Idempotency-Key 存在。

## Main Flow

1. 读取或创建 Idempotency Record。
2. 生成 `TicketId`。
3. 生成 `TicketDisplayId`。
4. 调用 `Ticket.create(...)`。
5. 创建初始 `TicketSla`。
6. 保存 Ticket。
7. 保存初始状态历史。
8. 写入 `ticket.created.v1` Outbox Event。
9. 保存 Idempotency Result。
10. 提交事务。
11. 返回 Ticket Snapshot。

## Alternative Flow

相同 Requester、相同 Idempotency-Key、相同 Payload：

```text
返回原 Ticket
```

## Failure Flow

相同 Key 不同 Payload：

```text
IDEMPOTENCY_KEY_REUSED
```

## Transition

```text
SM-001 Initial → NEW
```

## Invariants

```text
BI-001–BI-008
BI-085
BI-088
BI-095
```

## Transaction Boundary

```text
Ticket
TicketSla
Initial Status History
Outbox Event
Idempotency Record
```

## Output Events

```text
TicketCreated
ticket.created.v1
```

---

# 5. UC-02 Get Ticket

## Actor

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
```

## Query

```text
GetTicketQuery(ticketId, actorContext)
```

## Main Flow

1. 验证 Ticket 是否存在。
2. 根据 Actor 计算可见范围。
3. 查询 Ticket Read Model。
4. 脱敏字段。
5. 返回 Ticket Detail。

## Authorization

- Employee 只能读取自己的 Ticket。
- Support 只能读取授权 Queue。
- Auditor 只读。
- Admin 和 Manager 按角色范围读取。

## Invariants

```text
BI-098
BI-099
BI-102
```

## Transaction

只读事务。

## Failure

```text
TICKET_NOT_FOUND
FORBIDDEN
```

---

# 6. UC-03 List Requester Tickets

## Actor

```text
EMPLOYEE
```

## Query

```text
ListRequesterTicketsQuery
├── requesterId
├── status?
├── page
└── pageSize
```

## Main Flow

1. 从安全上下文获取 RequesterId。
2. 查询仅属于该 Requester 的 Ticket。
3. 应用分页和排序。
4. 返回轻量 Summary。

## Security

不得接受客户端提供的任意 RequesterId 代替当前用户身份。

---

# 7. UC-04 List Support Queue Tickets

## Actor

```text
IT_SUPPORT
IT_ADMIN
IT_MANAGER
```

## Query

```text
ListSupportQueueTicketsQuery
├── queueId
├── statuses
├── priority?
├── assignee?
├── page
└── pageSize
```

## Main Flow

1. 验证 Actor 是否属于 Queue。
2. 查询 Support Queue Read Model。
3. 应用筛选和排序。
4. 返回 Ticket Summary。

## Failure

```text
FORBIDDEN_QUEUE_ACCESS
```

---

# 8. UC-05 Add Ticket Message

## Actor

```text
EMPLOYEE
IT_SUPPORT
AUTHORIZED_SERVICE
```

## Command

```text
AddTicketMessageCommand
├── ticketId
├── author
├── type
├── visibility
├── body
├── attachmentIds
├── replyToMessageId?
└── idempotencyKey
```

## Preconditions

- Ticket 存在。
- Actor 可以查看和回复。
- Message Body 合法。
- Attachment 已通过安全扫描或处于受控状态。

## Main Flow

1. 加载 Ticket。
2. 创建 `TicketMessage`。
3. 验证 Visibility。
4. 保存 Message。
5. 如果 Ticket 当前为 `WAITING_FOR_USER` 且 Message 为有效用户回复，则执行 UC-09。
6. 写入 Message Event。
7. 提交事务。

## Invariants

```text
BI-024–BI-027
BI-095
```

## Events

```text
ticket.message_added.v1
```

## Notes

普通 Support Message 不自动改变 Ticket 状态。

---

# 9. UC-06 Start Triage

## Actor

```text
AGENT_RUNTIME_SERVICE
IT_SUPPORT
```

## Trigger

```text
agent.workflow.started
或 StartTriageCommand
```

## Command

```text
StartTriageCommand
├── ticketId
├── workflowId
├── sourceEventId?
└── expectedVersion
```

## Preconditions

- Ticket 状态为 `NEW`。
- 当前无 Active Workflow。
- Workflow 属于该 Ticket。

## Main Flow

1. 去重 Event / Command。
2. 加载 Ticket。
3. 调用 `startTriaging(...)`。
4. 关联 Active Workflow。
5. 写 Status History。
6. 写 Outbox Event。
7. 提交。

## Transition

```text
SM-002 NEW → TRIAGING
```

## Invariants

```text
BI-017–BI-021
BI-089–BI-094
```

## Events

```text
ticket.triaging_started.v1
```

---

# 10. UC-07 Complete Classification

## Actor

```text
AGENT_RUNTIME_SERVICE
IT_SUPPORT
```

## Trigger

```text
ticket.classification.completed
```

## Command

```text
CompleteClassificationCommand
├── ticketId
├── workflowId
├── category
├── subcategory
├── priority
├── confidence
├── source
├── eventId
└── aggregateVersion
```

## Preconditions

- Ticket 为 `TRIAGING`。
- Workflow 匹配。
- Category / Subcategory 合法。
- Confidence 达到阈值或具备人工 Override。

## Main Flow

1. 去重 Event。
2. 校验 Workflow 和 Version。
3. 更新 Category、Subcategory、Priority。
4. 调用 `completeClassification(...)`。
5. 写 Category History。
6. 写 Status History。
7. 写 Outbox Events。
8. 标记 Event Processed。
9. 提交。

## Transition

```text
SM-003 TRIAGING → INVESTIGATING
```

## Invariants

```text
BI-008
BI-009
BI-019
BI-089–BI-096
```

## Events

```text
ticket.classified.v1
ticket.investigation_ready.v1
```

---

# 11. UC-08 Request More Information

## Actor

```text
AGENT_RUNTIME_SERVICE
IT_SUPPORT
```

## Command

```text
RequestUserInputCommand
├── ticketId
├── workflowId
├── requestId
├── reasonCode
├── prompt
├── resumeStatus
└── expectedVersion
```

## Preconditions

- Ticket 为 `TRIAGING`、`INVESTIGATING` 或 `ESCALATED`。
- RequestId 唯一。
- ResumeStatus 合法。
- 无冲突 Pending Action。

## Main Flow

1. 加载 Ticket。
2. 创建 Request Reference。
3. 调用 `requestUserInput(...)`。
4. 创建 Requester-visible Message。
5. 暂停 SLA。
6. 写 History 和 Outbox。
7. 提交。

## Transitions

```text
SM-004
SM-007
SM-031
```

## Invariants

```text
BI-023
BI-027
BI-082
BI-095
```

## Events

```text
ticket.user_reply_requested.v1
```

---

# 12. UC-09 Receive User Reply

## Actor

```text
EMPLOYEE
IT_SUPPORT
```

## Command

```text
ReceiveUserReplyCommand
├── ticketId
├── requestId
├── messageBody
├── attachmentIds
├── idempotencyKey
└── expectedVersion
```

## Preconditions

- Ticket 为 `WAITING_FOR_USER`。
- RequestId 匹配。
- Actor 为 Requester 或授权 Support。
- ResumeStatus 已保存。

## Main Flow

1. 验证 Idempotency-Key。
2. 加载 Ticket。
3. 创建 User Message。
4. 调用 `resumeAfterUserReply(...)`。
5. 清除 Open User Request。
6. 恢复 SLA。
7. 写 History。
8. 写 Outbox Event。
9. 提交。

## Transitions

```text
SM-005
SM-006
```

## Invariants

```text
BI-024–BI-027
BI-082
BI-085–BI-088
BI-095
```

## Events

```text
ticket.user_replied.v1
ticket.triage_resume_requested.v1
或
ticket.investigation_resume_requested.v1
```

---

# 13. UC-10 Associate Active Workflow

## Actor

```text
AGENT_RUNTIME_SERVICE
```

## Command

```text
AssociateWorkflowCommand
├── ticketId
├── workflowId
├── eventId
└── expectedVersion
```

## Use

仅用于创建、Reopen 或恢复过程中绑定合法 Workflow。

## Preconditions

- Ticket 允许关联 Workflow。
- 当前无其他 Active Workflow。
- Workflow 与 Ticket 匹配。

## Invariants

```text
BI-017–BI-022
```

## Failure

```text
ACTIVE_WORKFLOW_ALREADY_EXISTS
WORKFLOW_REFERENCE_MISMATCH
```

---

# 14. UC-11 Request Approval

## Actor

```text
POLICY_APPROVAL_SERVICE
AGENT_RUNTIME_SERVICE
```

## Trigger

```text
approval.requested
```

## Command

```text
RegisterApprovalRequestCommand
├── ticketId
├── workflowId
├── actionId
├── actionType
├── approvalId
├── riskLevel
├── requestedAt
├── expiresAt
└── eventId
```

## Preconditions

- Ticket 为 `INVESTIGATING`。
- 无其他 Pending Action。
- Action 属于 Active Workflow。
- ApprovalId 存在。
- Pending Action 不包含 Credential。

## Main Flow

1. 去重 Event。
2. 加载 Ticket。
3. 构建 `PendingActionReference`。
4. 调用 `waitForApproval(...)`。
5. 写 History 和 Outbox。
6. 标记 Event Processed。
7. 提交。

## Transition

```text
SM-008 INVESTIGATING → WAITING_FOR_APPROVAL
```

## Invariants

```text
BI-028–BI-035
BI-095–BI-097
```

## Event

```text
ticket.approval_wait_started.v1
```

---

# 15. UC-12 Handle Approval Granted

## Actor

```text
POLICY_APPROVAL_SERVICE
```

## Trigger

```text
approval.granted
```

## Command

```text
HandleApprovalGrantedCommand
├── ticketId
├── workflowId
├── actionId
├── actionType
├── approvalId
├── approvedAt
├── expiresAt
├── toolExecutionId
├── eventId
└── aggregateVersion
```

## Preconditions

- Ticket 为 `WAITING_FOR_APPROVAL`。
- Ticket、Workflow、Action 和 Approval 全部匹配。
- Approval 未过期。
- ToolExecutionId 已预留。

## Main Flow

1. 去重 Event。
2. 加载 Ticket。
3. 校验所有 Reference。
4. 调用 `authorizeExecution(...)`。
5. 写 History。
6. 写 `ticket.execution_ready.v1`。
7. 标记 Event Processed。
8. 提交。

## Transition

```text
SM-011 WAITING_FOR_APPROVAL → EXECUTING
```

## Invariants

```text
BI-032–BI-040
BI-043
BI-044
BI-086–BI-097
```

## Idempotency

重复 Approval Event 返回成功，不重复创建执行。

---

# 16. UC-13 Handle Approval Rejected

## Trigger

```text
approval.rejected
```

## Main Flow

1. 去重 Event。
2. 校验 Ticket、Workflow、Action、Approval。
3. 失效 Pending Action。
4. 返回 `INVESTIGATING`。
5. 写 History 和 Outbox。
6. 提交。

## Transition

```text
SM-012
```

## Events

```text
ticket.approval_rejected.v1
ticket.investigation_resume_requested.v1
```

---

# 17. UC-14 Handle Approval Expired

与 UC-13 类似。

## Trigger

```text
approval.expired
```

## Additional Rule

旧 ApprovalId 不得复用。

## Transition

```text
SM-013
```

---

# 18. UC-15 Start Auto-approved Tool Execution

## Actor

```text
POLICY_APPROVAL_SERVICE
```

## Trigger

```text
policy.action_auto_approved
```

## Preconditions

- Ticket 为 `INVESTIGATING`。
- Action 被 Policy 明确标记为 `AUTO_APPROVED`。
- Action 属于 Active Workflow。
- 当前无 Tool Execution。
- Ticket 未 Cancel / Close。

## Main Flow

1. 去重 Event。
2. 保存 Pending Action。
3. 保存 ToolExecutionId。
4. 进入 `EXECUTING`。
5. 写 History 和 Outbox。
6. 提交。

## Transition

```text
SM-009
```

## Invariants

```text
BI-028–BI-031
BI-040–BI-044
```

---

# 19. UC-16 Handle Tool Execution Success

## Actor

```text
TOOL_GATEWAY_SERVICE
```

## Trigger

```text
tool.execution.completed
result = SUCCESS
```

## Preconditions

- Ticket 为 `EXECUTING`。
- ToolExecution、Action、Workflow 和 Attempt 匹配。
- VerificationId 已创建。

## Main Flow

1. 去重 Event。
2. 加载 Ticket。
3. 校验 Tool Result。
4. 保存 Tool Result Reference。
5. 调用 `startVerification(...)`。
6. 写 History 和 Outbox。
7. 标记 Event Processed。
8. 提交。

## Transition

```text
SM-014 EXECUTING → VERIFYING
```

## Invariants

```text
BI-041
BI-042
BI-045
BI-048–BI-051
```

## Event

```text
ticket.verification_started.v1
```

---

# 20. UC-17 Handle Tool Execution Failure

## Trigger

```text
tool.execution.failed
resultCertainty = KNOWN_NO_SIDE_EFFECT
```

## Main Flow

1. 去重 Event。
2. 验证 Tool Result 与当前 Action。
3. 保存 Failure Summary。
4. 失效 Pending Action。
5. 返回 `INVESTIGATING`。
6. 写 History 和 Outbox。
7. 提交。

## Transition

```text
SM-015
```

## Event

```text
ticket.investigation_resume_requested.v1
```

---

# 21. UC-18 Handle Unknown Tool Result

## Trigger

```text
tool.execution.result_unknown
```

## Preconditions

外部 Side Effect 是否发生无法确认。

## Main Flow

1. 去重 Event。
2. 保存 ToolExecution Reference 和未知状态。
3. 调用 `escalate(...)`。
4. 保留所有证据。
5. 写 History 和 Outbox。
6. 提交。

## Transition

```text
SM-017 EXECUTING → ESCALATED
```

## Invariants

```text
BI-046
BI-069
BI-072–BI-075
```

## Event

```text
ticket.escalated.v1
```

---

# 22. UC-19 Start Verification

## Actor

```text
AGENT_RUNTIME_SERVICE
IT_SUPPORT
```

## Trigger

```text
agent.resolution_candidate_ready
或 HumanFixCompletedCommand
```

## Preconditions

- Ticket 为 `INVESTIGATING` 或 `ESCALATED`。
- 有 Resolution Candidate 或 Manual Fix Summary。
- 无未处理 Pending Action。
- VerificationId 已创建。

## Main Flow

1. 加载 Ticket。
2. 保存 Verification Reference。
3. 进入 `VERIFYING`。
4. 写 History 和 Outbox。
5. 提交。

## Transitions

```text
SM-010
SM-032
```

---

# 23. UC-20 Handle Verification Success

## Actor

```text
VERIFICATION_SERVICE
```

## Trigger

```text
verification.completed
result = SUCCESS
```

## Preconditions

- Ticket 为 `VERIFYING`。
- Verification 匹配 Ticket、Workflow 和 Attempt。
- Evidence 完整。
- 无 Pending Action。

## Main Flow

1. 去重 Event。
2. 加载 Ticket。
3. 构造 `VerificationEvidence`。
4. 构造 `TicketResolution`。
5. 调用 `resolve(...)`。
6. 完成当前 Workflow 关联。
7. 标记当前 SLA Cycle 为 MET。
8. 创建 Auto-close Job Reference。
9. 写 History 和 Outbox。
10. 标记 Event Processed。
11. 提交。

## Transition

```text
SM-018 VERIFYING → RESOLVED
```

## Invariants

```text
BI-048–BI-060
BI-086–BI-097
```

## Events

```text
ticket.resolved.v1
```

---

# 24. UC-21 Handle Verification Failure

## Trigger

```text
verification.completed
result = FAILURE
```

## Branch A：可重试

条件：

```text
attemptCount <= 2
且无安全风险
```

转换：

```text
SM-019 VERIFYING → INVESTIGATING
```

## Branch B：超过上限或存在安全风险

转换：

```text
SM-020 VERIFYING → ESCALATED
```

## Main Flow

1. 去重 Event。
2. 校验 Verification Reference。
3. 保存 Failure Evidence。
4. 根据 Attempt Count 决定状态。
5. 写 History 和 Outbox。
6. 提交。

## Invariants

```text
BI-049–BI-054
```

---

# 25. UC-22 Resolve Ticket

## Note

本 Use Case 不是独立公共 API。

它是 UC-20 内部调用的 Domain/Application 行为。

## Rule

只有 Verification Success 可以调用。

禁止：

```text
Tool Success
Agent Assertion
Support Manual Flag
```

直接 Resolve。

## Domain Method

```text
ticket.resolve(evidence, resolution, now)
```

---

# 26. UC-23 Confirm and Close Ticket

## Actor

```text
EMPLOYEE
IT_SUPPORT
```

## Command

```text
ConfirmResolutionCommand
├── ticketId
├── actor
├── idempotencyKey
└── expectedVersion
```

## Preconditions

- Ticket 为 `RESOLVED`。
- Actor 为 Requester 或授权 Support。
- 无已接受 Reopen。

## Main Flow

1. 验证 Idempotency-Key。
2. 加载 Ticket。
3. 调用 `close(...)`。
4. 写 History 和 Outbox。
5. 提交。

## Transition

```text
SM-022 RESOLVED → CLOSED
```

## Invariants

```text
BI-057–BI-060
BI-085–BI-095
```

## Event

```text
ticket.closed.v1
```

---

# 27. UC-24 Auto-close Ticket

## Actor

```text
SYSTEM_SCHEDULER
```

## Trigger

Ticket 进入 `RESOLVED` 后 72 小时。

## Command

```text
AutoCloseTicketCommand
├── ticketId
├── resolutionCycleId
├── jobKey
└── expectedVersion
```

## Preconditions

- Ticket 仍为 `RESOLVED`。
- `resolvedAt <= now - 72h`。
- 期间无有效 Reopen。
- 期间无 Requester Activity。

## Main Flow

1. 获取 Candidate Ticket。
2. 使用稳定 Job Key 去重。
3. 加载最新 Ticket。
4. 重新验证 Guard。
5. 调用 `close(AUTO_CLOSE_TIMEOUT, SYSTEM, now)`。
6. 写 History 和 Outbox。
7. 提交。

## Transition

```text
SM-023
```

## Failure

Version Conflict：

```text
重新加载并重新判断，不盲目覆盖
```

---

# 28. UC-25 Reopen Ticket

## Actor

```text
EMPLOYEE
IT_SUPPORT
```

## Command

```text
ReopenTicketCommand
├── ticketId
├── reason
├── actor
├── newWorkflowId
├── idempotencyKey
└── expectedVersion
```

## Preconditions

- Ticket 为 `RESOLVED`，或
- Ticket 为 `CLOSED` 且关闭不超过 7 天。
- Actor 为 Requester 或授权 Support。
- Reason 存在。
- New WorkflowId 已创建。
- 无旧 Pending Action。

## Main Flow

1. 验证 Idempotency-Key。
2. 加载 Ticket。
3. 检查 Reopen Window。
4. 归档前一 Resolution Cycle。
5. 调用 `reopen(...)`。
6. 创建新 SLA Cycle。
7. 关联新 Workflow。
8. 清零 Verification Attempt。
9. 写 History 和 Outbox。
10. 提交。

## Transitions

```text
SM-024
SM-025
```

## Invariants

```text
BI-061–BI-066
BI-084
BI-085–BI-095
```

## Events

```text
ticket.reopened.v1
ticket.investigation_resume_requested.v1
```

---

# 29. UC-26 Cancel Ticket

## Actor

```text
EMPLOYEE
IT_SUPPORT
```

## Command

```text
CancelTicketCommand
├── ticketId
├── reason
├── actor
├── idempotencyKey
└── expectedVersion
```

## Allowed States

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
FAILED
ESCALATED
```

## Preconditions

- Actor 授权。
- Reason 存在。
- 当前没有未知 Tool Side Effect。

## Main Flow

1. 验证 Idempotency-Key。
2. 加载 Ticket。
3. 调用 Cancellation Policy。
4. 失效 Pending Action。
5. 请求取消 Active Workflow。
6. 取消 SLA Cycle。
7. 调用 `cancel(...)`。
8. 写 History 和 Outbox。
9. 提交。

## Transition

```text
SM-026
```

## Invariants

```text
BI-067–BI-071
BI-085–BI-097
```

## Special Case

如果当前为 `EXECUTING` 或 Side Effect 未知：

```text
不直接 Cancel
→ UC-27 Escalate Ticket
```

---

# 30. UC-27 Escalate Ticket

## Actor

```text
IT_SUPPORT
SYSTEM_POLICY
AGENT_RUNTIME_SERVICE
TOOL_GATEWAY_SERVICE
VERIFICATION_SERVICE
```

## Command

```text
EscalateTicketCommand
├── ticketId
├── target
├── reason
├── actor
├── evidenceReferences
└── expectedVersion
```

## Preconditions

- Target 存在。
- Reason 存在。
- 当前上下文可保存。
- Actor 或 Service 有权触发。

## Main Flow

1. 加载 Ticket。
2. 验证状态是否允许 Escalate。
3. 保存 Context Snapshot Reference。
4. 调用 `escalate(...)`。
5. 限制后续自动高风险操作。
6. 写 History 和 Outbox。
7. 提交。

## Transitions

```text
SM-017
SM-020
SM-029
SM-033
SM-034
```

## Invariants

```text
BI-072–BI-075
```

## Event

```text
ticket.escalated.v1
```

---

# 31. UC-28 Assign Ticket

## Actor

```text
IT_SUPPORT
IT_MANAGER
```

## Command

```text
AssignTicketCommand
├── ticketId
├── teamId
├── supportUserId?
├── actor
└── expectedVersion
```

## Preconditions

- Actor 有 Queue 管理权限。
- Team 合法。
- Support User 属于 Team 或具备授权。

## Main Flow

1. 加载 Ticket。
2. 构造 Assignment。
3. 调用 Assignment Behavior。
4. 保存 Current Assignment。
5. 写 Assignment History。
6. 写 Outbox Event。
7. 提交。

## Invariants

```text
BI-076–BI-079
```

## Event

```text
ticket.assigned.v1
```

---

# 32. UC-29 Retry Failed Automation

## Actor

```text
IT_SUPPORT
SYSTEM_RETRY_POLICY
```

## Command

```text
RetryFailedAutomationCommand
├── ticketId
├── workflowId
├── retryReason
├── actor
└── expectedVersion
```

## Preconditions

- Ticket 为 `FAILED`。
- Retry Budget 未耗尽。
- 失败原因已解决或为暂时性错误。
- Workflow 路径合法。

## Main Flow

1. 加载 Ticket。
2. 读取 Failure Reference。
3. 验证 Retry Policy。
4. 关联恢复或新 Workflow。
5. 返回 `INVESTIGATING`。
6. 写 History 和 Outbox。
7. 提交。

## Transition

```text
SM-028
```

## Alternative

Retry Budget 耗尽：

```text
UC-27 Escalate Ticket
SM-029
```

---

# 33. UC-30 Retrieve Ticket Timeline

## Actor

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
```

## Query

```text
GetTicketTimelineQuery
├── ticketId
├── actorContext
├── page?
└── cursor?
```

## Sources

```text
TicketStatusHistory
TicketMessage
AssignmentHistory
Approval Summary
Tool Execution Summary
Verification Summary
Escalation Record
Resolution Cycle
SLA History
```

## Main Flow

1. 验证 Ticket 访问权限。
2. 查询 Timeline Read Model。
3. 根据角色过滤内部记录。
4. 按时间排序。
5. 返回 Cursor-based Result。

## Rules

- Timeline 不是业务 Source of Truth。
- Employee 不可查看内部 Support-only 内容。
- Auditor 可以查看审计字段，但不能修改。

---

# 34. Use Case 与状态转换映射

| Use Case | Transition |
|---|---|
| UC-01 | SM-001 |
| UC-06 | SM-002 |
| UC-07 | SM-003 |
| UC-08 | SM-004 / SM-007 / SM-031 |
| UC-09 | SM-005 / SM-006 |
| UC-11 | SM-008 |
| UC-12 | SM-011 |
| UC-13 | SM-012 |
| UC-14 | SM-013 |
| UC-15 | SM-009 |
| UC-16 | SM-014 |
| UC-17 | SM-015 |
| UC-18 | SM-017 |
| UC-19 | SM-010 / SM-032 |
| UC-20 | SM-018 |
| UC-21 | SM-019 / SM-020 / SM-021 |
| UC-23 | SM-022 |
| UC-24 | SM-023 |
| UC-25 | SM-024 / SM-025 |
| UC-26 | SM-026 |
| UC-27 | SM-017 / SM-020 / SM-029 / SM-033 / SM-034 |
| UC-29 | SM-028 |

---

# 35. Command 与 Query 分类

## Commands

```text
CreateTicketCommand
AddTicketMessageCommand
StartTriageCommand
CompleteClassificationCommand
RequestUserInputCommand
ReceiveUserReplyCommand
AssociateWorkflowCommand
RegisterApprovalRequestCommand
HandleApprovalGrantedCommand
HandleApprovalRejectedCommand
HandleApprovalExpiredCommand
StartAutoApprovedExecutionCommand
HandleToolExecutionSuccessCommand
HandleToolExecutionFailureCommand
HandleUnknownToolResultCommand
StartVerificationCommand
HandleVerificationSuccessCommand
HandleVerificationFailureCommand
ConfirmResolutionCommand
AutoCloseTicketCommand
ReopenTicketCommand
CancelTicketCommand
EscalateTicketCommand
AssignTicketCommand
RetryFailedAutomationCommand
```

## Queries

```text
GetTicketQuery
ListRequesterTicketsQuery
ListSupportQueueTicketsQuery
GetTicketTimelineQuery
```

---

# 36. Application Service 建议

```text
CreateTicketApplicationService
TicketQueryService
TicketMessageApplicationService
TicketWorkflowApplicationService
ApprovalEventApplicationService
ToolResultApplicationService
VerificationApplicationService
TicketClosureApplicationService
TicketEscalationApplicationService
TicketAssignmentApplicationService
TicketTimelineQueryService
```

Application Service 负责协调：

- Aggregate
- Repository
- Security Context
- Idempotency Store
- Processed Event Store
- Status History
- Outbox

它不直接执行：

- LLM
- Tool
- RabbitMQ Publish
- LangSmith Export

---

# 37. Observability 要求

每个 Use Case 至少记录：

```text
use_case.id
ticket.id
workflow.id
command.id 或 event.id
actor.type
result
error.code
duration
```

不得将 TicketId 作为 Prometheus Label。

推荐 Span：

```text
ticket.use_case.execute
```

---

# 38. 测试要求

每个 Command Use Case 至少覆盖：

- Happy Path
- Unauthorized
- Invalid Input
- Invalid State
- Missing Reference
- Duplicate Command/Event
- Stale Event
- Optimistic Lock Conflict
- Outbox Failure
- Transaction Rollback

Query Use Case 覆盖：

- Authorized visibility
- Forbidden visibility
- Pagination
- Redaction
- Empty result
- Read-model lag tolerance

---

# 39. MVP Golden Path Use Case 链

```text
UC-01 Create Ticket
→ UC-06 Start Triage
→ UC-07 Complete Classification
→ UC-11 Request Approval
→ UC-12 Handle Approval Granted
→ UC-16 Handle Tool Execution Success
→ UC-20 Handle Verification Success
→ UC-23 Confirm and Close Ticket
```

异常分支：

```text
UC-08 Request More Information
UC-09 Receive User Reply
UC-13 Approval Rejected
UC-17 Tool Failure
UC-18 Unknown Tool Result
UC-21 Verification Failure
UC-27 Escalate Ticket
```

---

# 40. 验收标准

- [x] MVP Command Use Cases 已定义。
- [x] MVP Query Use Cases 已定义。
- [x] Actor 和 Authorization 已定义。
- [x] Preconditions 已定义。
- [x] Main、Alternative 和 Failure Flow 已定义。
- [x] State Machine ID 已映射。
- [x] Business Invariant ID 已引用。
- [x] Transaction Boundary 已定义。
- [x] Domain / Integration Event 已定义。
- [x] Idempotency 和 Concurrency 已定义。
- [x] Observability 和 Testing 要求已定义。
- [x] Golden Path Use Case 链已定义。

---

# 41. 下一步

下一份文档：

```text
05-api-contracts_CN.md
05-api-contracts_EN.md
```

API Contract 必须映射到本文档中的 `UC-xx`，不能创建没有 Use Case 支撑的业务 API。
