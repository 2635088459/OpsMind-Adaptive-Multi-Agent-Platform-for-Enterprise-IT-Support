# OpsMind 底层详细系统设计 README

> **项目名称：** OpsMind — Adaptive Multi-Agent Platform for Enterprise IT Support  
> **设计阶段：** Low-Level Design（LLD，底层详细系统设计）  
> **MVP 场景：** Duo / Okta 登录与 MFA 故障的自动调查、审批、执行与验证  
> **目标：** 将高层架构进一步细化为可编码、可测试、可审计、可恢复的模块与服务设计

---

## 1. 当前阶段定位

OpsMind 已完成第一版高层设计，包括：

- System Context Diagram
- 六层架构
- 八个逻辑业务板块
- MVP Golden Path
- 初步服务边界
- 事件驱动架构方向
- 数据所有权原则
- Policy、Tool、Memory 与 Evaluation 的总体位置

高层设计回答：

> 系统有哪些主要部分，它们如何协作？

底层详细设计需要继续回答：

> 每个板块内部如何实现，拥有哪些数据，暴露哪些接口，如何处理状态、失败、并发、安全和测试？

---

## 2. LLD 设计目标

LLD 完成后，每个领域应该做到：

- 可以直接转换成代码目录、类和服务
- 可以明确数据库表与字段
- 可以明确 API 和 Event Schema
- 可以明确状态转换
- 可以明确失败恢复策略
- 可以明确权限和审批规则
- 可以明确 Logs、Metrics 和 Traces
- 可以明确 Unit、Integration、Contract 和 Failure Tests
- 可以明确 MVP 与 Future Scope

---

## 3. 推荐详细设计顺序

不要按照八个板块编号平均展开。推荐顺序如下：

```text
第一组：核心业务链路
1. Ticket 与业务工作流
2. Agent Runtime 与任务编排
3. 工具集成与执行网关
4. Policy、审批与安全治理

第二组：智能能力
5. Memory 与企业知识系统

第三组：用户与平台支撑
6. 用户入口与身份认证
7. 可观测性与平台基础设施

第四组：质量与演进
8. Evaluation 与受控自主改进
```

首先确保下面的闭环在设计上完全成立：

```text
Ticket
→ Agent Investigation
→ Policy Check
→ Human Approval
→ Tool Execution
→ Verification
→ Resolution
```

---

## 4. 统一领域设计模板

每个领域文档必须包含：

1. Purpose
2. Responsibilities
3. Non-responsibilities
4. Internal Components
5. Core Workflows
6. State Model
7. Data Ownership
8. API Contracts
9. Event Contracts
10. Security Rules
11. Failure Handling
12. Observability
13. Testing Strategy
14. MVP Scope
15. Future Scope
16. Open Questions

---

# 5. Ticket 与业务工作流

## 5.1 Purpose

Ticket 是整个系统的业务载体。所有用户问题、Agent 调查、审批、执行、验证和关闭都必须绑定 Ticket。

Ticket Service 只负责业务状态，不负责 LLM 推理或企业工具执行。

## 5.2 Responsibilities

- 创建、查询和更新 Ticket
- 管理 Ticket 状态机
- 保存用户消息
- 保存状态历史
- 管理 SLA
- 处理取消、升级、关闭和重新打开
- 发布 Ticket 事件
- 使用 Transactional Outbox 保证事件最终发布

## 5.3 Non-responsibilities

- 调用 LLM
- 查询 Okta 或 Duo
- 生成根因判断
- 执行企业管理员操作
- 审批敏感操作
- 写入长期记忆

## 5.4 Internal Components

```text
TicketController
TicketApplicationService
TicketDomainService
TicketStateMachine
TicketRepository
TicketMessageService
TicketAssignmentService
SlaService
TicketStatusHistoryService
OutboxService
OutboxPublisher
```

## 5.5 Ticket Aggregate

```json
{
  "ticketId": "INC-2048",
  "requesterId": "user-1024",
  "title": "Cannot log in to Housing Portal",
  "description": "Duo authentication keeps failing",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "MEDIUM",
  "status": "INVESTIGATING",
  "workflowId": "wf-7788",
  "assignedTeam": "IAM_SUPPORT",
  "version": 4,
  "createdAt": "2026-07-21T10:20:00Z",
  "updatedAt": "2026-07-21T10:24:00Z"
}
```

## 5.6 Ticket 状态机

```text
NEW
→ TRIAGING
→ INVESTIGATING
→ WAITING_FOR_USER
→ WAITING_FOR_APPROVAL
→ EXECUTING
→ VERIFYING
→ RESOLVED
→ CLOSED
```

异常状态：

```text
ESCALATED
FAILED
CANCELLED
REOPENED
```

## 5.7 合法状态转换

| 当前状态 | 目标状态 | 触发条件 |
|---|---|---|
| NEW | TRIAGING | `ticket.created` |
| TRIAGING | INVESTIGATING | 分类成功 |
| INVESTIGATING | WAITING_FOR_USER | 信息不足 |
| INVESTIGATING | WAITING_FOR_APPROVAL | 需要敏感操作 |
| WAITING_FOR_APPROVAL | EXECUTING | 审批通过 |
| EXECUTING | VERIFYING | Tool 执行完成 |
| VERIFYING | RESOLVED | 验证成功 |
| VERIFYING | INVESTIGATING | 验证失败但可以继续调查 |
| RESOLVED | CLOSED | 用户确认或自动关闭 |
| RESOLVED | REOPENED | 用户报告问题复发 |

非法转换包括：

```text
NEW → RESOLVED
CANCELLED → EXECUTING
CLOSED → INVESTIGATING
WAITING_FOR_APPROVAL → RESOLVED
```

## 5.8 数据表

```text
ticket.tickets
ticket.ticket_messages
ticket.ticket_status_history
ticket.ticket_assignments
ticket.sla_records
ticket.outbox_events
```

## 5.9 API

```http
POST /api/v1/tickets
GET  /api/v1/tickets/{ticketId}
GET  /api/v1/tickets
POST /api/v1/tickets/{ticketId}/messages
POST /api/v1/tickets/{ticketId}/cancel
POST /api/v1/tickets/{ticketId}/reopen
POST /internal/v1/tickets/{ticketId}/transitions
```

## 5.10 发布事件

```text
ticket.created
ticket.updated
ticket.user_replied
ticket.cancelled
ticket.reopened
ticket.status_changed
ticket.resolved
ticket.closed
```

## 5.11 消费事件

```text
ticket.classified
approval.requested
approval.granted
approval.rejected
tool.execution.completed
tool.execution.failed
verification.completed
agent.workflow.failed
```

## 5.12 并发控制

使用 Optimistic Locking：

```text
UPDATE ticket
SET status = ?, version = version + 1
WHERE ticket_id = ? AND version = ?
```

更新失败时重新加载 Ticket，并重新判断目标转换是否仍然合法。

## 5.13 测试

- 合法状态转换
- 非法状态转换
- 重复事件
- 并发更新
- Transactional Outbox
- Ticket 取消后阻止执行
- Verification 失败后不得关闭

---

# 6. Agent Runtime 与任务编排

## 6.1 Purpose

Agent Runtime 是所有 Agent 的运行与控制平台，负责规划、委派、状态保存、暂停、恢复、重试、预算和 Agent Handoff。

## 6.2 Responsibilities

- 创建 Agent Workflow
- 加载 Agent Definition
- 调度 Agent Task
- 管理 Worker Pool
- 保存执行状态与 Checkpoint
- 暂停和恢复 Workflow
- 控制 Timeout、Retry 和 Budget
- 合并多 Agent 结果
- 检测无进展循环
- 保存模型调用和执行轨迹

## 6.3 Non-responsibilities

- 直接修改 Ticket 表
- 批准自己的操作
- 直接访问 Okta 或 Duo
- 保存管理员凭证
- 自己宣布业务成功

## 6.4 Internal Components

```text
WorkflowManager
WorkflowStateMachine
AgentCoordinator
TaskPlanner
AgentRegistry
WorkerDispatcher
CheckpointManager
ContextBuilder
ModelGateway
BudgetController
RetryManager
HandoffManager
ProgressDetector
StructuredOutputValidator
```

## 6.5 MVP Agents

```text
Triage Agent
Identity Agent
Knowledge Agent
Resolution Agent
Verification Agent
```

## 6.6 Workflow Model

```json
{
  "workflowId": "wf-7788",
  "ticketId": "INC-2048",
  "workflowType": "IDENTITY_MFA_INVESTIGATION",
  "status": "RUNNING",
  "currentStep": "IDENTITY_CHECK",
  "iterationCount": 3,
  "toolCallCount": 5,
  "tokenCostUsd": 0.18,
  "maxIterations": 10,
  "maxToolCalls": 20,
  "budgetUsd": 1.00,
  "version": 7
}
```

## 6.7 Workflow 状态机

```text
PENDING
→ RUNNING
→ WAITING_FOR_EVENT
→ PAUSED
→ RUNNING
→ COMPLETED
```

异常状态：

```text
RETRYING
TIMED_OUT
FAILED
CANCELLED
```

## 6.8 Checkpoint 内容

```text
workflow_id
current_step
completed_tasks
pending_tasks
facts
hypotheses
rejected_hypotheses
tool_results
approval_request
token_usage
iteration_count
next_action
created_at
```

## 6.9 暂停和恢复

```text
Resolution Agent proposes Duo Reset
→ Runtime requests Policy Decision
→ Approval required
→ Save checkpoint
→ Workflow = PAUSED
→ Wait for approval.granted
→ Load checkpoint
→ Validate ticket is still active
→ Resume tool execution
```

## 6.10 Task 幂等

推荐键：

```text
workflow_id + agent_name + task_type + task_version
```

## 6.11 防止无限循环

```yaml
max_iterations: 10
max_tool_calls: 20
max_cost_usd: 1.00
timeout_minutes: 15
no_progress_limit: 2
```

## 6.12 Agent 输出 Schema

```json
{
  "agent": "identity-agent",
  "taskId": "task-301",
  "status": "COMPLETED",
  "facts": [
    {
      "type": "DUO_ENROLLMENT",
      "value": "EXPIRED",
      "source": "tool:get_duo_enrollment"
    }
  ],
  "hypotheses": [
    {
      "cause": "EXPIRED_DUO_ENROLLMENT",
      "confidence": 0.91
    }
  ],
  "recommendedNextActions": [
    "SEARCH_SIMILAR_TICKETS",
    "REQUEST_DUO_RESET"
  ]
}
```

## 6.13 数据表

```text
agent.workflows
agent.workflow_steps
agent.agent_tasks
agent.agent_task_attempts
agent.checkpoints
agent.agent_handoffs
agent.model_calls
agent.task_idempotency
```

## 6.14 发布事件

```text
agent.workflow.started
agent.workflow.paused
agent.workflow.resumed
agent.workflow.completed
agent.workflow.failed
agent.task.requested
agent.task.completed
agent.task.failed
resolution.proposed
verification.requested
```

## 6.15 消费事件

```text
ticket.created
ticket.user_replied
ticket.cancelled
approval.granted
approval.rejected
tool.execution.completed
tool.execution.failed
```

## 6.16 测试

- Workflow 状态转换
- Worker Crash 恢复
- Checkpoint Resume
- 重复 Task 去重
- Structured Output Validation
- Budget Limit
- No-progress Detection
- 多 Agent 并行结果合并
- Agent 结果冲突
- Ticket 取消后的 Workflow 终止

---

# 7. 工具集成与执行网关

## 7.1 Purpose

Tool Gateway 是 Agent 与企业系统之间的唯一执行入口。

## 7.2 Responsibilities

- 注册工具
- 验证输入 Schema
- 检查 Policy Decision
- 检查 Approval
- 管理 Idempotency
- 安全注入凭证
- 路由到 Connector
- 保存执行结果
- 标准化外部响应
- 处理 Timeout 和 Retry

## 7.3 Internal Components

```text
ToolRegistry
ToolRequestValidator
PolicyClient
ApprovalValidator
IdempotencyManager
CredentialProvider
ConnectorRouter
ExecutionManager
ToolResultNormalizer
ExecutionRepository
ConnectorHealthMonitor
```

## 7.4 MVP Tools

```text
get_account_status
check_group_membership
get_duo_enrollment
query_login_failures
reset_duo_enrollment
verify_login
send_user_instructions
```

## 7.5 Tool Definition

```json
{
  "toolName": "reset_duo_enrollment",
  "version": "1.0",
  "riskLevel": "MEDIUM",
  "approvalRequired": true,
  "requiredRole": "IT_ADMIN",
  "timeoutSeconds": 20,
  "retryPolicy": "VERIFY_BEFORE_RETRY",
  "idempotencyRequired": true
}
```

## 7.6 Execution Flow

```text
Receive request
→ Validate schema
→ Validate caller identity
→ Load tool definition
→ Check policy decision
→ Check approval
→ Check idempotency
→ Load credential
→ Call connector
→ Normalize result
→ Save execution record
→ Publish completion event
```

## 7.7 Tool Request

```json
{
  "executionId": "exec-6001",
  "ticketId": "INC-2048",
  "workflowId": "wf-7788",
  "toolName": "reset_duo_enrollment",
  "toolVersion": "1.0",
  "arguments": {
    "userId": "user-1024"
  },
  "approvalId": "approval-81",
  "idempotencyKey": "INC-2048:wf-7788:reset_duo:user-1024:v1",
  "requestedBy": "resolution-agent-v1"
}
```

## 7.8 Tool 已执行但响应丢失

1. 不立即重试。
2. 查询外部系统状态。
3. 已完成则恢复旧结果。
4. 确认未完成才重试。
5. 无法确认则转人工。

## 7.9 Credential Isolation

- Credential 只存在于 Credential Provider。
- Credential 不能进入 Prompt。
- Agent 不能读取 Token。
- 日志不能记录 Secret。
- Connector 使用最小权限身份。

## 7.10 数据表

```text
tool.tool_registry
tool.tool_executions
tool.tool_execution_attempts
tool.execution_idempotency
tool.connector_health
```

## 7.11 发布事件

```text
tool.execution.started
tool.execution.completed
tool.execution.failed
tool.execution.unknown
connector.health_changed
```

## 7.12 测试

- Schema Validation
- 未审批写操作拒绝
- 过期审批拒绝
- Idempotency
- Lost Response Recovery
- Credential Leakage
- Connector Timeout
- Circuit Breaker
- Read Tool Retry
- Write Tool Verify-before-retry

---

# 8. Policy、审批与安全治理

## 8.1 Purpose

将提出操作、批准操作和执行操作彻底分离。

## 8.2 Responsibilities

- 风险分类
- RBAC / ABAC
- 创建审批请求
- 管理审批生命周期
- 检查审批人资格
- 保存 Policy Decision
- Guardrails
- Kill Switch
- Audit Event

## 8.3 Internal Components

```text
PolicyEngine
RiskClassifier
AuthorizationEvaluator
ApprovalManager
ApprovalStateMachine
ApprovalExpirationScheduler
SeparationOfDutiesValidator
AuditWriter
GuardrailEngine
KillSwitchManager
```

## 8.4 风险等级

### Low

- 查询账号
- 查询日志
- 检索知识库
- 发送标准说明

### Medium

- Reset MFA
- Unlock Account
- Clear Session

### High

- 修改权限组
- 授予管理权限
- 批量账号操作

### Forbidden

- 删除关键账号
- 绕过 MFA
- 导出密钥
- 关闭审计
- Agent 自行提升权限

## 8.5 Policy Decision

```json
{
  "policyDecisionId": "pd-401",
  "action": "reset_duo_enrollment",
  "riskLevel": "MEDIUM",
  "decision": "REQUIRES_APPROVAL",
  "requiredRole": "IT_ADMIN",
  "approvalTtlMinutes": 30,
  "reason": "The action changes user authentication state",
  "policyVersion": "2026.07.1"
}
```

## 8.6 Approval 状态

```text
PENDING
APPROVED
REJECTED
EXPIRED
CANCELLED
EXECUTED
```

## 8.7 Separation of Duties

- 用户不能批准自己的高风险操作。
- Agent 不能批准写操作。
- 高风险权限变更可以要求双人审批。
- 审批人必须拥有目标系统权限。

## 8.8 数据表

```text
policy.policies
policy.policy_versions
policy.policy_decisions
policy.approval_requests
policy.approval_decisions
policy.role_bindings
policy.kill_switches
audit.audit_events
```

## 8.9 发布事件

```text
approval.requested
approval.granted
approval.rejected
approval.expired
approval.cancelled
policy.denied
kill_switch.activated
```

## 8.10 测试

- Low Risk 自动允许
- Medium Risk 需要审批
- Forbidden Action 拒绝
- Approval Expiration
- Duplicate Approval
- Ticket 取消后审批失效
- Self-approval 拒绝
- Kill Switch
- Policy Version Tracking

---

# 9. Memory 与企业知识系统

## 9.1 Purpose

让 Agent 在当前 Ticket 中保持连续状态，并在未来 Ticket 中复用经过验证的经验。

## 9.2 Internal Components

```text
WorkingMemoryStore
MemoryExtractor
MemoryValidator
MemoryConsolidator
ConflictDetector
RetrievalEngine
KnowledgeIngestionPipeline
DocumentChunker
EmbeddingService
MemoryVersionManager
RetentionManager
PiiRedactor
```

## 9.3 Working Memory

```json
{
  "ticketId": "INC-2048",
  "version": 6,
  "facts": [],
  "hypotheses": [],
  "rejectedHypotheses": [],
  "completedTasks": [],
  "pendingTasks": [],
  "toolResults": [],
  "approvalDecisions": [],
  "contextSummary": ""
}
```

## 9.4 Long-Term Memory Types

- Episodic Memory
- Semantic Memory
- Procedural Memory
- Organizational Memory
- Agent Performance Memory

## 9.5 Memory Write Pipeline

```text
Ticket resolved
→ Extract candidate
→ Remove PII
→ Validate evidence
→ Deduplicate
→ Detect conflicts
→ Score confidence
→ Evaluate usefulness
→ Store versioned memory
```

## 9.6 Retrieval Scoring

```text
semantic_similarity
category_match
application_match
recency
source_trust
human_validation
resolution_success
```

## 9.7 数据表

```text
memory.working_memory
memory.memories
memory.memory_versions
memory.memory_sources
memory.memory_conflicts
memory.knowledge_documents
memory.document_chunks
memory.embeddings
memory.retrieval_logs
```

## 9.8 测试

- Working Memory Versioning
- Concurrent Merge
- PII Redaction
- Duplicate Memory
- Conflicting Memory
- Retrieval Precision
- Provenance
- Expiration
- Degraded Mode
- Invalid Memory Rejection

---

# 10. 用户入口与身份认证

## 10.1 Internal Components

```text
EmployeePortal
AdminConsole
AuthenticationClient
AuthorizationGuard
TicketConversationUI
ApprovalCenter
InvestigationTimeline
AuditViewer
MetricsDashboard
RealtimeUpdateClient
```

## 10.2 Employee Pages

- Create Ticket
- My Tickets
- Ticket Detail
- Reply to Agent
- Upload Evidence
- Confirm Resolution
- Reopen Ticket

## 10.3 IT Admin Pages

- Ticket Queue
- Approval Center
- Investigation Timeline
- Evidence Viewer
- Tool Execution History
- Audit Viewer
- Memory Evidence

## 10.4 Manager Pages

- SLA Dashboard
- Resolution Metrics
- Escalation Rate
- Agent Performance
- Approval Waiting Time

## 10.5 Authentication

- OIDC
- Authorization Code Flow
- Access Token
- Refresh Token
- Role Claims
- Service Identity

## 10.6 Realtime Updates

MVP 推荐 SSE：

```text
ticket.status_changed
approval.requested
tool.execution.completed
verification.completed
```

## 10.7 安全要求

- 用户只能查看自己的 Ticket。
- IT Support 只能查看所属队列。
- IT Admin 只能审批授权范围内操作。
- Auditor 只读。
- 前端不展示凭证。
- 普通用户不查看内部 Prompt。

---

# 11. 可观测性与平台基础设施

## 11.1 Correlation Identifiers

```text
trace_id
correlation_id
ticket_id
workflow_id
agent_task_id
tool_execution_id
approval_id
```

## 11.2 Logs

结构化日志至少包含：

```json
{
  "timestamp": "",
  "service": "",
  "level": "",
  "traceId": "",
  "ticketId": "",
  "workflowId": "",
  "event": "",
  "message": "",
  "errorCode": ""
}
```

禁止记录：

- Password
- Access Token
- API Secret
- 完整 PII
- 未脱敏 Prompt

## 11.3 Metrics

### Business

- Ticket Volume
- Mean Time to Resolution
- SLA Compliance
- Reopen Rate
- Human Escalation Rate
- Approval Waiting Time

### Agent

- Agent Success Rate
- Model Latency
- Token Cost
- Tool Calls
- Loop Iterations
- Workflow Resume Count
- Memory Retrieval Hit Rate
- Verification Failure Rate

### Infrastructure

- API Latency
- Error Rate
- Queue Lag
- Worker Utilization
- Database Connections
- Redis Health
- Connector Health

## 11.4 MVP Infrastructure

```text
PostgreSQL + pgvector
Redis
RabbitMQ
OpenTelemetry Collector
Prometheus
Grafana
Loki
Tempo or Jaeger
Docker Compose
```

---

# 12. Evaluation 与受控自主改进

## 12.1 Internal Components

```text
DatasetRegistry
TestCaseRunner
DeterministicGraders
LlmJudge
PolicyComplianceGrader
TrajectoryEvaluator
RegressionComparator
AgentVersionRegistry
ImprovementProposalGenerator
CanaryManager
RollbackManager
```

## 12.2 Evaluation Dimensions

- Ticket Classification Accuracy
- Root Cause Accuracy
- Tool Selection Correctness
- Tool Argument Correctness
- Policy Compliance
- Memory Retrieval Precision
- Resolution Success
- Reopen Rate
- Human Escalation Rate
- Token Cost
- Latency
- Handoff Information Loss

## 12.3 Test Case Schema

```json
{
  "caseId": "eval-001",
  "userRequest": "Duo keeps failing",
  "mockSystemState": {
    "accountStatus": "ACTIVE",
    "groupMembership": "CORRECT",
    "duoEnrollment": "EXPIRED"
  },
  "expectedCategory": "IDENTITY_ACCESS",
  "expectedRootCause": "EXPIRED_DUO_ENROLLMENT",
  "allowedTools": [
    "get_account_status",
    "check_group_membership",
    "get_duo_enrollment",
    "reset_duo_enrollment"
  ],
  "requiredApproval": true,
  "verificationCondition": "LOGIN_SUCCESS"
}
```

## 12.4 对照实验

```text
Baseline: Single Agent
Version A: Single Agent + RAG
Version B: Single Agent + Memory
Version C: Multi-Agent + Memory
Full: Multi-Agent + Memory + Policy + Improvement
```

## 12.5 Improvement Flow

```text
Collect traces
→ Classify failures
→ Generate candidate
→ Create version
→ Run benchmark
→ Compare with baseline
→ Detect regression
→ Human approval
→ Canary
→ Promote or rollback
```

## 12.6 Release Gates

- Root Cause Accuracy 不下降
- Policy Violation = 0
- False Tool Execution 不增加
- Resolution Rate 提升或保持
- Token Cost 增幅不超过阈值
- 关键测试全部通过

---

# 13. 服务间通信规范

## 13.1 同步通信

用于立即返回结果：

- Portal → Ticket Service
- Agent Runtime → Memory Search
- Agent Runtime → Policy Evaluation
- Tool Gateway → Mock Enterprise API
- Admin Console → Approval API

MVP 使用 REST 即可。

## 13.2 异步通信

```text
ticket.created
agent.workflow.started
agent.task.completed
approval.requested
approval.granted
tool.execution.completed
verification.completed
ticket.resolved
memory.candidate.created
evaluation.requested
```

## 13.3 Event Envelope

```json
{
  "eventId": "evt-1001",
  "eventType": "approval.granted",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-21T10:30:00Z",
  "producer": "policy-service",
  "traceId": "trace-abc",
  "correlationId": "INC-2048",
  "ticketId": "INC-2048",
  "workflowId": "wf-7788",
  "payload": {}
}
```

每个 Event 必须定义：

- Producer
- Consumers
- Version
- Payload
- Idempotency Key
- Ordering
- Retry
- DLQ
- PII Classification

---

# 14. 数据所有权与一致性

## 14.1 Schema Ownership

```text
ticket.*      → Ticket Service
agent.*       → Agent Runtime
memory.*      → Memory Service
tool.*        → Tool Gateway
policy.*      → Policy Service
evaluation.*  → Evaluation Service
audit.*       → Audit Module
```

## 14.2 规则

- 服务只写自己的 Schema。
- 跨服务更新通过 API 或 Event。
- MVP 可以共用一个 PostgreSQL 实例。
- Audit 数据 Append-only。
- 关键写操作使用 Version 或 Idempotency Key。

## 14.3 一致性模型

- 单服务内部使用强一致事务。
- 跨服务采用最终一致。
- Event Publication 使用 Transactional Outbox。
- Message Delivery 使用 At-least-once。
- Consumer 必须幂等。
- 高风险外部操作使用 Verify-before-retry。

---

# 15. 错误处理与恢复

| 故障 | 处理方式 |
|---|---|
| LLM Timeout | Retry、Fallback、人工升级 |
| Invalid JSON | Repair，失败则 Task Failed |
| Worker Crash | Checkpoint Resume |
| Duplicate Event | Idempotency |
| Event Send Failure | Transactional Outbox |
| Duplicate Approval | Optimistic Lock |
| Tool Response Lost | Verify External State |
| Memory Down | Degraded Mode |
| Agent Loop | Iteration / Cost / No-progress Limit |
| Repeated Failure | DLQ + Human Escalation |
| Verification Failed | Return to Investigation |
| Ticket Cancelled | Cancel Pending Work |

---

# 16. 测试策略

## 16.1 Unit Tests

- State Machine
- Policy Rules
- Schema Validation
- Memory Scoring
- Idempotency
- Retry Logic

## 16.2 Integration Tests

- Ticket → Agent Workflow
- Agent → Policy
- Policy → Approval
- Tool Gateway → Mock Duo
- Verification → Ticket Resolution
- Memory Candidate Creation

## 16.3 Contract Tests

- REST API Contract
- Event Schema
- Connector Contract
- Agent Structured Output

## 16.4 Failure Injection

- Kill Agent Worker
- Delay RabbitMQ
- Drop Tool Response
- Stop Memory Service
- Return Invalid LLM JSON
- Duplicate Event
- Expire Approval

## 16.5 End-to-End

```text
Create Ticket
→ Investigate
→ Approve
→ Reset
→ Verify
→ Resolve
→ Write Memory
→ Evaluate
```

---

# 17. 推荐开发顺序

## Phase 1 — Ticket Foundation

- Ticket Aggregate
- State Machine
- Repository
- REST API
- Outbox

## Phase 2 — Agent Runtime Skeleton

- Workflow
- Task
- Worker
- Checkpoint
- Triage Agent

## Phase 3 — Mock Enterprise Tools

- Mock Okta
- Mock Duo
- Tool Registry
- Query Tools

## Phase 4 — Policy and Approval

- Risk Rules
- Approval API
- Workflow Pause / Resume

## Phase 5 — Write Tool and Verification

- Reset Duo
- Idempotency
- Verify Login
- Ticket Resolution

## Phase 6 — Memory

- Working Memory
- Knowledge RAG
- Memory Candidate

## Phase 7 — Observability

- Trace
- Metrics
- Agent Timeline

## Phase 8 — Evaluation

- Dataset
- Graders
- Baseline Comparison

---

# 18. LLD 完成标准

一个领域只有满足以下条件才算完成：

- [ ] Purpose 已定义
- [ ] Responsibilities 已定义
- [ ] Non-responsibilities 已定义
- [ ] Internal Components 已定义
- [ ] Core Workflows 已定义
- [ ] State Model 已定义
- [ ] Data Ownership 已定义
- [ ] API Contracts 已定义
- [ ] Event Contracts 已定义
- [ ] Security Rules 已定义
- [ ] Failure Handling 已定义
- [ ] Observability 已定义
- [ ] Testing Strategy 已定义
- [ ] MVP Scope 已定义
- [ ] Future Scope 已定义
- [ ] Open Questions 已记录

---

# 19. 推荐仓库结构

```text
OpsMind/
├── README.md
├── docs/
│   ├── high-level-design/
│   ├── low-level-design/
│   │   ├── README_CN.md
│   │   ├── README_EN.md
│   │   ├── domains/
│   │   │   ├── 01-user-access-authentication.md
│   │   │   ├── 02-ticket-workflow.md
│   │   │   ├── 03-agent-runtime-orchestration.md
│   │   │   ├── 04-memory-knowledge.md
│   │   │   ├── 05-tool-integration-gateway.md
│   │   │   ├── 06-policy-approval-governance.md
│   │   │   ├── 07-evaluation-improvement.md
│   │   │   └── 08-observability-platform.md
│   │   ├── api/
│   │   ├── events/
│   │   ├── data-model/
│   │   └── diagrams/
│   └── adr/
├── apps/
├── services/
├── packages/
├── infrastructure/
└── tests/
```

---

# 20. 下一步

第一个需要完成的详细领域文档是：

```text
docs/low-level-design/domains/02-ticket-workflow.md
```

然后按顺序完成：

```text
03-agent-runtime-orchestration.md
05-tool-integration-gateway.md
06-policy-approval-governance.md
04-memory-knowledge.md
01-user-access-authentication.md
08-observability-platform.md
07-evaluation-improvement.md
```

Ticket Workflow 稳定后，其他领域才能可靠定义自己的状态变化、事件和完成条件。
