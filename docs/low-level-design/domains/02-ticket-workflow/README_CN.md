# OpsMind — 02 Ticket Workflow 详细设计

> **领域：** Ticket & Business Workflow  
> **阶段：** Low-Level Design  
> **版本：** 1.0  
> **状态：** Draft for Domain Design  
> **依赖：** `technology-baseline`  
> **建议路径：** `docs/low-level-design/domains/02-ticket-workflow/README_CN.md`

---

## 1. 文档目的

本文档是 OpsMind `Ticket Workflow` 领域的总设计入口。

它负责定义：

- Ticket Workflow 在 MVP 中承担的业务职责
- Ticket 与 Agent Workflow、Approval、Tool Execution、Verification 的关系
- Ticket 的状态生命周期
- 该领域拥有的数据、API 和 Event
- Transactional Outbox、并发控制与幂等原则
- Security、Observability 与 Testing 的总体要求
- 后续子设计文档的拆分方式和完成顺序

本文档是领域导航页，不替代后续的 Domain Model、状态机、API、Event、数据库和代码级详细设计。

---

## 2. 在 OpsMind Golden Path 中的位置

```text
Employee submits login issue
→ Ticket is created
→ Ticket enters TRIAGING
→ Agent Runtime starts investigation
→ Ticket enters INVESTIGATING
→ Wait for user or approval
→ Approved tool action executes
→ Ticket enters EXECUTING
→ Verification begins
→ Ticket enters VERIFYING
→ Verification succeeds
→ Ticket enters RESOLVED
→ User confirms or timeout closes the ticket
→ Ticket enters CLOSED
```

Ticket 是整个系统的业务主轴：

```text
User Access
→ 创建和查看 Ticket

Agent Runtime
→ 调查 Ticket

Policy & Approval
→ 授权 Ticket 对应的敏感操作

Tool Gateway
→ 为 Ticket 执行企业操作

Verification Agent
→ 判断 Ticket 是否可以解决

Memory
→ 从已解决 Ticket 中提取经验

Evaluation
→ 评估 Ticket 处理质量

Observability
→ 跨服务追踪 Ticket
```

---

## 3. 领域目标

Ticket Workflow 必须保证：

1. 每个用户问题都有唯一 Ticket。
2. Ticket 状态只能通过合法转换改变。
3. 每次状态变化都写入历史记录。
4. Ticket 状态与 Agent Workflow 状态保持分离。
5. 重复 Event 不会产生重复业务效果。
6. 并发更新不会静默覆盖。
7. Tool 执行成功不等于 Ticket 已解决。
8. Verification 成功前不能进入 `RESOLVED`。
9. Ticket 取消后，新的敏感 Tool Action 必须被阻止。
10. 业务状态和 Outbox Event 在同一事务中保存。
11. 所有关键变化可审计、可追踪、可恢复。

---

## 4. Responsibilities

Ticket Workflow 负责：

- 创建、查询和更新 Ticket
- 管理 Ticket State Machine
- 保存用户消息
- 保存 Ticket Status History
- 管理 Assignment 和基础 SLA
- 管理 Cancel、Escalate、Resolve、Close 和 Reopen
- 关联当前 Active Workflow
- 消费其他领域的业务 Event
- 发布 Ticket Domain Event
- Transactional Outbox
- Optimistic Locking
- API Idempotency
- Event Idempotency
- Ticket Timeline
- Employee、Support、Admin 和 Auditor 所需 API

## 4.1 Non-responsibilities

Ticket Workflow 不负责：

- LLM 调用
- Agent 选择和推理
- Okta、Duo 或企业系统查询
- Tool 执行
- Tool 风险分类
- Approval 决策
- 企业 Credential
- Long-term Memory
- LangSmith Agent Trace
- Agent Evaluation
- 基础设施监控

---

## 5. 与其他领域的边界

### User Access & Authentication

负责登录、Token、角色、前端路由。Ticket Workflow 负责 Ticket 业务数据。

### Agent Runtime

负责 Workflow、Task、Checkpoint、Pause/Resume 和调查。Agent Runtime 不得直接写 `ticket.*`。

### Policy & Approval

负责 Risk、Approval Request 和 Decision。Ticket Workflow 根据 Approval Event 更新业务状态。

### Tool Gateway

负责 Credential、Tool Execution 和 Tool Idempotency。Ticket Workflow 只处理 Tool Result。

### Memory & Knowledge

负责 Working Memory、Long-term Memory 和 RAG。Ticket Workflow 只发布生命周期 Event。

### Evaluation

负责 Dataset、Experiment 和 Agent 质量评估。Ticket Workflow 提供最终状态和历史事实。

### Observability

Ticket Workflow 使用 OpenTelemetry，不直接依赖 LangSmith SDK。Agent Runtime 使用 `ticket_id`、`workflow_id` 和 `trace_id` 关联 LangSmith。

---

## 6. 核心业务对象概览

详细定义放在 `01-domain-model/`。

### Aggregate Root

```text
Ticket
```

### Candidate Entities

```text
Ticket
TicketMessage
TicketAssignment
SLARecord
TicketStatusHistory
```

### Candidate Value Objects

```text
TicketId
RequesterId
TicketTitle
TicketDescription
TicketCategory
TicketSubcategory
TicketPriority
TicketStatus
ApplicationCode
AssignmentTeam
WorkflowId
ApprovalId
ResolutionSummary
```

### Candidate Domain Events

```text
TicketCreated
TicketClassified
TicketStatusChanged
TicketWaitingForUser
TicketWaitingForApproval
TicketExecutionReady
TicketVerificationStarted
TicketResolved
TicketClosed
TicketCancelled
TicketReopened
TicketEscalated
```

Aggregate Boundary 必须在 `01-domain-model/` 中最终确认。

---

## 7. Ticket 状态概览

详细状态机放在 `03-state-machine/`。

### Main States

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
EXECUTING
VERIFYING
RESOLVED
CLOSED
```

### Exception / Recovery States

```text
ESCALATED
FAILED
CANCELLED
REOPENED
```

### Golden Path

```text
NEW
→ TRIAGING
→ INVESTIGATING
→ WAITING_FOR_APPROVAL
→ EXECUTING
→ VERIFYING
→ RESOLVED
→ CLOSED
```

### Common Branches

```text
INVESTIGATING
→ WAITING_FOR_USER
→ INVESTIGATING
```

```text
VERIFYING
→ INVESTIGATING
```

```text
RESOLVED
→ REOPENED
→ INVESTIGATING
```

```text
ANY_ACTIVE_STATE
→ ESCALATED
```

---

## 8. Ticket State 与 Workflow State 分离

| Ticket Status | Workflow Status | 含义 |
|---|---|---|
| TRIAGING | RUNNING | Triage Agent 正在分类 |
| INVESTIGATING | RUNNING | Agent 正在调查 |
| WAITING_FOR_USER | PAUSED | 等待用户回复 |
| WAITING_FOR_APPROVAL | PAUSED | 等待管理员审批 |
| EXECUTING | RUNNING | Tool 正在执行 |
| VERIFYING | RUNNING | Verification 正在检查 |
| RESOLVED | COMPLETED | 业务和技术流程完成 |
| ESCALATED | FAILED / PAUSED | 转人工处理 |
| CANCELLED | CANCELLED | 流程终止 |

规则：

- Ticket Service 不使用 Workflow Status 替代 Ticket Status。
- Agent Runtime 不直接修改 Ticket Status。
- 两者通过 API 和 Event 协作。
- 跨服务采用最终一致性，但必须通过业务不变量保护。

---

## 9. MVP Use Cases

详细内容放在 `04-use-cases/`。

```text
UC-01 Create Ticket
UC-02 Get Ticket
UC-03 List Requester Tickets
UC-04 List Support Queue Tickets
UC-05 Add Ticket Message
UC-06 Classify Ticket
UC-07 Request More Information
UC-08 Receive User Reply
UC-09 Associate Active Workflow
UC-10 Request Approval
UC-11 Handle Approval Granted
UC-12 Handle Approval Rejected
UC-13 Handle Approval Expired
UC-14 Handle Tool Execution Completed
UC-15 Handle Tool Execution Failed
UC-16 Start Verification
UC-17 Handle Verification Success
UC-18 Handle Verification Failure
UC-19 Resolve Ticket
UC-20 Close Ticket
UC-21 Reopen Ticket
UC-22 Cancel Ticket
UC-23 Escalate Ticket
UC-24 Retrieve Ticket Timeline
```

---

## 10. API 概览

详细 Contract 放在 `05-api-contracts/`。

### Public APIs

```http
POST /api/v1/tickets
GET  /api/v1/tickets/{ticketId}
GET  /api/v1/tickets
POST /api/v1/tickets/{ticketId}/messages
POST /api/v1/tickets/{ticketId}/cancel
POST /api/v1/tickets/{ticketId}/reopen
POST /api/v1/tickets/{ticketId}/confirm-resolution
GET  /api/v1/tickets/{ticketId}/timeline
```

### Support APIs

```http
GET  /api/v1/support/tickets
POST /api/v1/support/tickets/{ticketId}/assign
POST /api/v1/support/tickets/{ticketId}/escalate
POST /api/v1/support/tickets/{ticketId}/close
```

### Internal APIs

```http
POST /internal/v1/tickets/{ticketId}/classify
POST /internal/v1/tickets/{ticketId}/transitions
POST /internal/v1/tickets/{ticketId}/workflows/{workflowId}/associate
GET  /internal/v1/tickets/{ticketId}/status
```

所有 Internal API 仍然需要 Service Identity。

---

## 11. Event 概览

详细 Contract 放在 `06-event-contracts/`。

### Published Events

```text
ticket.created
ticket.classified
ticket.status_changed
ticket.user_reply_requested
ticket.user_replied
ticket.approval_wait_started
ticket.execution_ready
ticket.verification_started
ticket.resolved
ticket.closed
ticket.cancelled
ticket.reopened
ticket.escalated
```

### Consumed Events

```text
agent.workflow.started
agent.workflow.failed
ticket.classification.completed
approval.requested
approval.granted
approval.rejected
approval.expired
tool.execution.completed
tool.execution.failed
verification.completed
```

### Event Envelope

```json
{
  "eventId": "evt-1001",
  "eventType": "ticket.created",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-23T16:30:00Z",
  "producer": "ticket-workflow-service",
  "traceId": "trace-abc",
  "correlationId": "INC-2048",
  "ticketId": "INC-2048",
  "workflowId": null,
  "aggregateId": "ticket-uuid",
  "aggregateVersion": 1,
  "payload": {}
}
```

---

## 12. Data Ownership 概览

详细设计放在 `07-data-model/`。

```text
ticket.tickets
ticket.ticket_messages
ticket.ticket_status_history
ticket.ticket_assignments
ticket.sla_records
ticket.outbox_events
ticket.processed_events
ticket.idempotency_records
```

规则：

- 只有 Ticket Workflow 可以写 `ticket.*`。
- 其他服务不能直接更新 Ticket Table。
- 跨服务修改通过 API 或 Event。
- 避免跨服务 Schema Foreign Key。
- Status History Append-only。
- Mutable Aggregate 使用 `version`。
- Outbox Event 与业务状态同事务写入。

---

## 13. Transaction 与 Outbox

详细设计放在 `08-transaction-and-outbox/`。

### Create Ticket Transaction

```text
BEGIN
Insert Ticket
Insert Initial Status History
Insert ticket.created Outbox Event
Insert API Idempotency Record
COMMIT
```

### State Transition Transaction

```text
BEGIN
Load Ticket with expected version
Validate transition
Update Ticket
Insert Status History
Insert Outbox Event
COMMIT
```

数据库事务中禁止：

- 直接发送 RabbitMQ
- 调用 Agent Runtime
- 调用 LLM
- 调用 Tool Gateway
- 调用 LangSmith
- 调用企业 API

Outbox Publisher：

```text
Read unpublished records
→ Publish to RabbitMQ
→ Confirm publish
→ Mark as published
```

---

## 14. Concurrency 与 Idempotency

详细设计放在 `09-concurrency-and-idempotency/`。

### Optimistic Locking

```sql
UPDATE ticket.tickets
SET status = :newStatus,
    version = version + 1,
    updated_at = :updatedAt
WHERE ticket_id = :ticketId
  AND version = :expectedVersion;
```

### API Idempotency

适用于：

- Create Ticket
- Cancel Ticket
- Reopen Ticket
- Confirm Resolution

```http
Idempotency-Key: request-123
```

### Event Idempotency

```text
UNIQUE(consumer_name, event_id)
```

Event 必须携带：

```text
aggregateVersion
```

用于检测 Duplicate、Out-of-order 和 Stale Update。

---

## 15. 初步业务不变量

完整规则放在 `02-business-invariants/`。

1. Ticket 必须有 Requester。
2. Title 和 Description 必填。
3. 同一 Ticket 同时只能有一个 Active Workflow。
4. `CANCELLED` 不能进入 `EXECUTING`。
5. `CLOSED` 不能被后台 Event 自动 Reopen。
6. Verification 成功前不能进入 `RESOLVED`。
7. `WAITING_FOR_APPROVAL` 必须关联 Approval Request。
8. Approval 必须匹配当前 Ticket、Workflow 和 Action。
9. 每次状态变化必须写 Status History。
10. Duplicate Event 不能重复改变 Ticket。
11. Ticket Cancel 后必须阻止新的敏感 Tool Action。
12. Reopen 必须记录 Reason。
13. Category 变化必须保留历史。
14. Tool Success 不等于 Resolution Success。
15. Verification Failure 必须回到 Investigation 或 Escalation。
16. Ticket Service 不读取企业 Credential。
17. Audit 和 Status History 不可被普通更新覆盖。

---

## 16. Security 概览

详细设计放在 `11-security/`。

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
SERVICE_IDENTITY
```

规则：

- Employee 只能查看自己的 Ticket。
- Support 只能查看授权 Queue。
- Admin 只能执行授权范围内的操作。
- Auditor 只读。
- Internal API 使用 Service Identity。
- Internal Event 验证来源和 Schema。
- PII 最小化。
- Log 不记录 Token、Credential 或完整敏感描述。
- 发送到 LangSmith 的 Ticket Metadata 必须脱敏。

---

## 17. Observability 概览

详细设计放在 `12-observability/`。

Ticket Workflow 使用：

```text
OpenTelemetry
Prometheus
Structured JSON Logs
```

Ticket Service 不直接依赖 LangSmith。

Trace Context：

```text
trace_id
correlation_id
ticket_id
workflow_id
event_id
aggregate_version
requester_id_hash
```

Metrics：

```text
ticket_created_total
ticket_transition_total
ticket_transition_failed_total
ticket_cancelled_total
ticket_reopened_total
ticket_resolved_total
ticket_resolution_duration_seconds
ticket_waiting_for_user_duration_seconds
ticket_waiting_for_approval_duration_seconds
outbox_pending_count
outbox_publish_failure_total
duplicate_event_total
out_of_order_event_total
optimistic_lock_conflict_total
```

Agent Runtime 将 Ticket 关联字段写入 LangSmith Metadata。

---

## 18. Failure Handling 概览

详细设计放在 `10-failure-handling/`。

必须覆盖：

- Database Failure
- Outbox Insert Failure
- RabbitMQ Unavailable
- Outbox Publish Failure
- Duplicate Event
- Out-of-order Event
- Optimistic Lock Conflict
- Agent Workflow Failure
- Approval Expired / Rejected
- Tool Execution Failed / Unknown
- Verification Failure
- Cancel During Execution
- SSE Delivery Failure
- LangSmith Unavailable
- OpenTelemetry Export Failure

原则：

```text
PostgreSQL 保存业务状态
跨服务允许最终一致
Duplicate Delivery 必须安全
Telemetry Failure 不阻塞业务
Security Failure 必须拒绝操作
```

---

## 19. Testing 概览

详细内容放在 `14-testing-strategy/`。

### Unit Tests

- Ticket Aggregate
- Value Objects
- Business Invariants
- State Machine
- Domain Policies
- Error Mapping

### Integration Tests

- PostgreSQL
- Flyway
- Outbox
- RabbitMQ
- Idempotent Consumer
- Optimistic Lock
- Keycloak Authorization

### Contract Tests

- OpenAPI
- Event JSON Schema
- Error Envelope
- Internal API

### Failure Injection

- Stop RabbitMQ
- Duplicate / Delay / Reorder Event
- Crash Outbox Publisher
- Cause Optimistic Lock Conflict
- Expire Approval
- Return Verification Failure

### End-to-End

```text
Create Ticket
→ Classify
→ Investigate
→ Request Approval
→ Approve
→ Execute Tool
→ Verify
→ Resolve
→ Close
```

---

## 20. 技术实现基线

```text
Language: Java 21
Framework: Spring Boot 3.5.x
Persistence: PostgreSQL + Spring Data JPA
Migration: Flyway
Messaging: RabbitMQ + Spring AMQP
Security: Spring Security + Keycloak JWT
Resilience: Resilience4j
Observability: OpenTelemetry
Testing: JUnit 5 + Mockito + Testcontainers + ArchUnit
Build: Maven Wrapper
```

---

## 21. 推荐代码结构

```text
services/ticket-workflow-service/
├── pom.xml
├── Dockerfile
├── src/main/java/com/opsmind/ticket/
│   ├── api/
│   ├── application/
│   │   ├── command/
│   │   ├── query/
│   │   ├── handler/
│   │   └── service/
│   ├── domain/
│   │   ├── model/
│   │   ├── valueobject/
│   │   ├── event/
│   │   ├── policy/
│   │   ├── repository/
│   │   └── exception/
│   ├── infrastructure/
│   │   ├── persistence/
│   │   ├── messaging/
│   │   ├── outbox/
│   │   ├── security/
│   │   └── observability/
│   └── config/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
└── src/test/
```

---

## 22. 文档目录

```text
02-Ticket-Workflow/
├── README_CN.md
├── README_EN.md
├── 01-domain-model/
├── 02-business-invariants/
├── 03-state-machine/
├── 04-use-cases/
├── 05-api-contracts/
├── 06-event-contracts/
├── 07-data-model/
├── 08-transaction-and-outbox/
├── 09-concurrency-and-idempotency/
├── 10-failure-handling/
├── 11-security/
├── 12-observability/
├── 13-package-and-class-design/
├── 14-testing-strategy/
└── diagrams/
```

推荐顺序：

```text
Domain Model
→ Business Invariants
→ State Machine
→ Use Cases
→ API
→ Events
→ Data Model
→ Transaction and Outbox
→ Concurrency and Idempotency
→ Failure Handling
→ Security
→ Observability
→ Package/Class Design
→ Testing
```

---

## 23. MVP 范围

### In Scope

- Identity / MFA Ticket
- Employee Ticket Creation
- Classification Result
- User Message
- Waiting for User
- Waiting for Approval
- Tool Result
- Verification Result
- Resolve / Close / Cancel / Reopen / Escalate
- Status History
- Basic SLA
- Transactional Outbox
- Idempotency
- Optimistic Locking
- OpenTelemetry

### Out of Scope

- ServiceNow Clone
- Multi-tenancy
- Workflow Builder
- Advanced SLA Editor
- Multi-region Availability
- Event Sourcing
- Full CQRS
- Kafka
- Production Okta / Duo
- Direct LangSmith SDK in Ticket Service
- Advanced Search
- Complex Reporting

---

## 24. 设计完成标准

- [ ] Aggregate Root 确定
- [ ] Entity / Value Object 确定
- [ ] Aggregate Boundary 确定
- [ ] Business Invariants 确定
- [ ] State Machine 确定
- [ ] Illegal Transition 确定
- [ ] Use Cases 确定
- [ ] Command / Query 确定
- [ ] API Contract 确定
- [ ] Event Contract 确定
- [ ] Data Model 确定
- [ ] Transaction Boundary 确定
- [ ] Outbox 确定
- [ ] Optimistic Lock 确定
- [ ] API / Event Idempotency 确定
- [ ] Failure Handling 确定
- [ ] Security 确定
- [ ] Observability 确定
- [ ] Package / Class Design 确定
- [ ] Test Plan 确定
- [ ] Golden Path Sequence 通过 Review

---

## 25. 下一步

完成本 README 后，优先生成：

```text
01-domain-model/
02-business-invariants/
03-state-machine/
```

这三份文档冻结后，再进入 Use Case、API、Event 和数据库设计。

在此之前不要开始编写完整 Spring Boot Service。
