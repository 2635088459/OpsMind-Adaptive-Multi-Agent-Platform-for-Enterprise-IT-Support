# OpsMind Ticket Workflow — Phase 01 Create Ticket Vertical Slice

> **文档编号：** IMP-TW-P01  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 01  
> **阶段名称：** Create Ticket Vertical Slice  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **前置条件：** Phase 00 Engineering Foundation Exit Criteria 已通过  
> **主要 Feature Spec：** `SPEC-TW-001-create-ticket`  
> **代码目录：** `services/ticket-workflow-service/`  
> **Spec 目录：** `docs/specs/domains/02-ticket-workflow/SPEC-TW-001-create-ticket/`  
> **Traceability：** `docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml`

---

# 1. 阶段目标

Phase 01 交付 Ticket Workflow 的第一个完整业务 Vertical Slice：

```text
Authenticated Employee
→ POST /api/v1/tickets
→ Authentication / Authorization
→ Idempotency
→ CreateTicketApplicationService
→ Ticket.create()
→ PostgreSQL
→ Initial Status History
→ Required Audit
→ Transactional Outbox
→ HTTP 201 Response
```

本阶段完成后，系统应能够：

- 接收 Employee 创建 IT Support Ticket 的请求。
- 从认证上下文获取 Requester Identity，而不是信任 Request Body。
- 创建初始状态为 `NEW` 的 Ticket。
- 在一个本地数据库事务中写入 Ticket、初始状态历史、审计、Outbox 和幂等结果。
- 返回稳定的 API Response、Location 和 ETag。
- 在重复请求、部分失败和并发请求下保持正确性。

---

# 2. 为什么先实现 Create Ticket

Create Ticket 是整个 Ticket 生命周期的业务入口。没有它，后续无法可靠实现：

- Triage
- Agent Workflow
- Waiting for User
- Approval
- Tool Execution
- Verification
- Resolution
- Reopen
- Escalation

该阶段还会第一次验证以下设计是否能真正落地：

```text
Domain Aggregate
API Contract
Application Service
Persistence Adapter
Flyway Migration
Transaction Boundary
API Idempotency
Audit
Transactional Outbox
Security
Observability
Testing Strategy
```

它是最小但完整的 Vertical Slice，能够较早发现：

- Domain Model 是否过度设计或缺少关键类型。
- Package Dependency 是否符合 Hexagonal Architecture。
- API DTO、Domain 和 JPA Entity 是否真正分离。
- Transactional Outbox 和 Idempotency 是否可实现。
- Security Context 是否能正确传递 Requester Identity。
- Unit、Integration 和 Contract Test 是否能够共同工作。

---

# 3. 前置条件

进入 Phase 01 前必须满足：

- Phase 00 Exit Review 通过。
- `./mvnw clean verify` 通过。
- PostgreSQL Testcontainer 可运行。
- RabbitMQ Testcontainer 可运行。
- ArchUnit 可运行。
- Spring Security Default Deny 已存在。
- Actuator Health 可用。
- Docker Image 可构建并启动。
- CI Workflow 可执行。
- 没有提前实现 Create Ticket 业务代码。

---

# 4. 设计引用

Phase 01 以现有 LLD 为设计基线。

## `01-domain-model`

用于：

- Ticket Aggregate
- TicketId
- DisplayTicketId
- RequesterId
- TicketTitle
- TicketDescription
- TicketStatus
- TicketSource
- CreatedAt
- Aggregate Version

## `02-business-invariants`

Feature Spec 必须列出与以下行为对应的精确 BI 编号：

- Requester Identity
- Initial State
- Server-generated Identity
- Initial History
- Audit
- Outbox
- Idempotency
- Secret Redaction

## `03-state-machine`

引用：

```text
Initial → NEW
```

客户端不能选择其他初始状态。

## `04-use-cases`

引用：

```text
UC-01 Create Ticket
```

## `05-api-contracts`

引用：

```text
POST /api/v1/tickets
```

包括 Request DTO、Response DTO、Error Envelope、`Idempotency-Key`、Authentication、Authorization、Validation 和 HTTP Status。

## `06-event-contracts`

引用：

```text
ticket.created.v1
```

Phase 01 必须将它写入 Outbox。RabbitMQ Publisher 是否在本阶段发布，由本计划第 8 节决定。

## `07-data-model`

本阶段至少实现：

```text
ticket.tickets
ticket.ticket_status_history
ticket.audit_records
ticket.outbox_events
ticket.idempotency_records
```

具体表名以最终 LLD 为准。

## `08-transaction-and-outbox`

实现：

```text
Ticket
+ Status History
+ Audit
+ Outbox
+ Idempotency Completion
```

在一个本地数据库事务中提交。

## `09-concurrency-and-idempotency`

实现：

- `actor_scope + idempotency_key`
- Canonical Request Hash
- Same Key / Same Payload Replay
- Same Key / Different Payload Conflict
- In-progress Handling
- Initial Optimistic Versioning

## `10-error-handling-and-reconciliation`

实现本阶段需要的 Validation、Authentication、Authorization、Idempotency、Database 和 Unexpected Error Mapping。

## `11-security-and-authorization`

实现：

- Employee JWT
- `tickets:create` Scope
- RequesterId from Security Context
- Mass Assignment Protection
- No Requester Identity Injection

## `12-observability-and-audit`

实现：

- HTTP Trace
- Application Span
- Structured Log
- Create Counter
- Duration Metric
- Required Audit
- PII / Secret Redaction

## `13-package-and-class-design`

实现 API Adapter、Application Service、Domain Aggregate、Outbound Ports、Persistence Adapter、Audit Adapter、Outbox Adapter 和 Security Adapter。

## `14-testing-strategy`

实现 Domain、Application、Controller、Security、PostgreSQL、Atomicity、Idempotency、Event Contract、Architecture 和 Observability Test。

---

# 5. Feature Spec

本阶段的业务 Source of Truth 是：

```text
SPEC-TW-001-create-ticket
```

推荐结构：

```text
docs/specs/domains/02-ticket-workflow/
└── SPEC-TW-001-create-ticket/
    ├── spec_CN.md
    ├── spec_EN.md
    ├── acceptance.feature
    └── examples/
        ├── valid-request.json
        ├── valid-response.json
        ├── invalid-request.json
        └── error-response.json
```

业务编码必须在 Spec Review 后开始。

---

# 6. Scope

Phase 01 包含：

- Create Ticket Command
- Initial `NEW` State
- Server-generated TicketId 和 Display ID
- Requester Identity from JWT
- Request Validation
- Authorization
- API Idempotency
- Ticket Persistence
- Initial Status History
- Required Audit Record
- `ticket.created.v1` Outbox Record
- HTTP Response 和 Error Envelope
- Trace、Log 和 Metrics
- Unit、Application、Integration、Contract、Security 和 Atomicity Tests

---

# 7. Non-goals

Phase 01 不实现：

- Ticket Query / List
- Add Message
- Triage / Classification
- Agent Workflow
- Waiting for User
- Approval
- Tool Execution
- Verification
- Resolve / Close / Reopen
- Cancel / Assign / Escalate
- RabbitMQ Consumer Set
- Reconciliation Workflow
- Full Keycloak Realm Hardening
- Dashboard / Alert Suite

也不允许为未来功能提前创建没有 Spec 和 Test 的业务方法。

---

# 8. Vertical Slice Boundary

入口：

```text
POST /api/v1/tickets
```

本阶段必须完成的业务边界：

```text
Committed Ticket
+
Committed Initial Status History
+
Committed Required Audit
+
Committed Outbox Record
+
Committed Idempotency Response
```

推荐 Phase 01 先完成 **Outbox 写入**，把通用 Outbox Publisher 放在 Phase 03 前完成，原因是 Phase 03 开始需要真实跨服务事件流。

因此 Phase 01 的强制要求是：

- 写入真实 Outbox Row。
- 验证 Event Contract。
- 验证事务原子性。
- 验证 EventId、AggregateVersion 和 Payload。

Phase 01 不要求：

- Publisher Confirm
- Retry Queue
- DLQ
- RabbitMQ Consumer

这些能力在第一次真实异步集成前完成。

---

# 9. API 行为

## 9.1 Request

示例：

```json
{
  "title": "Unable to connect to VPN",
  "description": "The VPN client shows authentication failed.",
  "categoryHint": "NETWORK_ACCESS",
  "source": "EMPLOYEE_PORTAL"
}
```

客户端不得提供：

- `ticketId`
- `displayId`
- `requesterId`
- `status`
- `priority`
- `assignedTeam`
- `assignedAgent`
- `workflowId`
- `approvalId`
- `createdAt`
- `version`

## 9.2 Headers

```text
Authorization: Bearer <JWT>
Idempotency-Key: <stable-client-generated-key>
Content-Type: application/json
```

## 9.3 Success Response

推荐：

```text
HTTP 201 Created
Location: /api/v1/tickets/{ticketId}
ETag: "<version>"
```

Response 只包含 Employee 有权查看并用于下一步交互的字段。

## 9.4 Error Response

统一使用 LLD 冻结的 Error Envelope。

至少覆盖：

- Invalid Body
- Missing / Invalid Token
- Missing Scope
- Missing Idempotency Key
- Same Key / Different Payload
- Request In Progress
- Database Failure
- Unexpected Internal Failure

不得返回 Stack Trace、SQL、Table Name、Raw JWT、Secret 或内部 Exception Class。

---

# 10. Domain Behavior

推荐 Factory：

```java
Ticket.create(
    TicketId ticketId,
    DisplayTicketId displayId,
    RequesterId requesterId,
    TicketTitle title,
    TicketDescription description,
    TicketSource source,
    Instant now
)
```

创建后必须满足：

```text
status = NEW
version = initial aggregate version
createdAt = now
updatedAt = now
requesterId = authenticated requester
activeWorkflow = none
pendingAction = none
resolution = none
```

Domain 产生：

```text
TicketCreatedDomainEvent
```

Application Layer 将其映射为：

```text
ticket.created.v1
```

---

# 11. Business Invariants

Feature Spec 必须列出精确 BI ID。本阶段至少保证：

- RequesterId 来自可信 Security Context。
- 初始状态只能是 `NEW`。
- TicketId 和 Display ID 由服务端生成。
- 创建时不存在 Active Workflow、Pending Action 和 Resolution。
- Aggregate Version 初始化正确。
- 成功创建必须存在 Initial Status History。
- Integration Event 必须写入 Outbox。
- 同一个 Idempotency Key 不得重复创建 Ticket。
- 相同 Key 不同 Payload 必须拒绝。
- Required Audit 必须写入。
- Secret 不得进入 Domain Event、Outbox、Log 或 Trace。

---

# 12. Transaction Boundary

推荐 Application Service：

```text
CreateTicketApplicationService
```

公共方法使用：

```text
@Transactional
```

事务步骤：

```text
1. Validate command
2. Authorize actor
3. Reserve or read Idempotency Key
4. Generate IDs
5. Ticket.create()
6. Persist Ticket
7. Persist Initial Status History
8. Persist Required Audit Record
9. Persist ticket.created.v1 Outbox Record
10. Complete Idempotency Record
11. Commit
```

任意必要数据库步骤失败：

```text
ROLLBACK ALL
```

事务内禁止：

- RabbitMQ Publish
- Agent Runtime Call
- Approval Service Call
- Tool Gateway Call
- External HTTP Call
- Waiting for Publisher Confirm

---

# 13. Idempotency

Scope：

```text
actor_scope + idempotency_key
```

Request Hash：

```text
Canonical JSON → SHA-256
```

行为：

```text
same key + same payload + completed
→ return stored response

same key + different payload
→ IDEMPOTENCY_KEY_REUSED

same key + fresh in-progress
→ REQUEST_IN_PROGRESS

stale in-progress + committed Ticket
→ rebuild or recover response

stale in-progress + no committed Ticket
→ retryable recovery path
```

并发目标：

```text
100 concurrent identical requests
→ exactly one Ticket
```

---

# 14. Persistence

至少创建：

```text
TicketJpaEntity
TicketStatusHistoryJpaEntity
AuditRecordJpaEntity
OutboxEventJpaEntity
IdempotencyRecordJpaEntity
```

以及：

```text
TicketPersistenceMapper
TicketPersistenceAdapter
StatusHistoryPersistenceAdapter
AuditPersistenceAdapter
OutboxPersistenceAdapter
IdempotencyPersistenceAdapter
```

约束：

- Domain Object 与 JPA Entity 分离。
- Controller 不使用 JPA Entity。
- Domain 不使用 JPA Annotation。
- Application Service 不直接依赖 Spring Data Repository。
- 使用明确 Port，不创建万能 Generic Repository。

---

# 15. Flyway Migration

推荐：

```text
V001__create_ticket_schema.sql
V002__create_ticket_table.sql
V003__create_ticket_status_history.sql
V004__create_audit_records.sql
V005__create_outbox_events.sql
V006__create_idempotency_records.sql
```

也可按 Slice 合并，但必须保持可 Review，不提前创建大量未来表。

必须验证：

- Primary Key
- Unique Constraint
- Check Constraint
- Foreign Key
- TIMESTAMPTZ
- Aggregate Version
- Request Hash
- Outbox EventId
- Audit Append-only Foundation

---

# 16. Outbox Event

Phase 01 Event：

```text
ticket.created.v1
```

Envelope 必须符合 `06-event-contracts`。

Payload 应最小化，建议包含：

- TicketId
- Display ID
- Pseudonymous Requester Reference
- Source
- Initial Status
- CreatedAt
- Aggregate Version
- CorrelationId

不得包含完整 Description、Raw JWT、Authorization Header 或 Secret。

必须验证：

- JSON Schema Draft 2020-12
- Event Version
- Routing Key
- Producer Identity
- Data Classification
- Aggregate Version
- No Secret

---

# 17. Security

## Authentication

要求有效 Employee JWT，并验证 Signature、Issuer、Audience、Expiration、Not Before 和必要 Client / Scope。

## Authorization

要求：

```text
tickets:create
```

或设计中批准的等效 Scope。

## Requester Identity

RequesterId 只能来自：

```text
SecurityPrincipal
```

不得来自 Request DTO。

## Mass Assignment

DTO 显式定义允许字段，Unknown Field Policy 必须与 API Contract 一致。

## Rate Limit

本阶段至少预留 Per-user Create Limit、Burst Protection 和 Abuse Metric；完整 Enforcement 可在 Phase 09 强化。

---

# 18. Audit

创建成功至少记录：

```text
action = TICKET_CREATED
actor
actorType
ticketId
commandId
traceId
clientId
scope
result = SUCCESS
occurredAt
```

安全失败可记录 Authentication Failure、Authorization Failure、Idempotency Abuse 和 Suspicious Payload。

Audit Metadata 不保存完整 Description。

如果 Required Audit Insert 失败：

```text
Create Ticket must fail closed
```

---

# 19. Observability

## Trace

建议 Span：

```text
POST /api/v1/tickets
CreateTicketUseCase
Ticket.create
ticket.transaction
db.ticket.insert
db.history.insert
db.audit.insert
db.outbox.insert
db.idempotency.complete
```

## Log

记录 TraceId、CorrelationId、CommandId、Safe Error Code、Result 和 Duration。

不记录 Raw Description、JWT、Secret、Requester Email 或 Database Password。

## Metrics

建议：

```text
opsmind_ticket_create_total
opsmind_ticket_create_duration_seconds
opsmind_ticket_create_failure_total
opsmind_ticket_idempotency_replay_total
opsmind_ticket_idempotency_conflict_total
```

Label 禁止使用 ticketId、requesterId、eventId 和 idempotencyKey。

---

# 20. TDD 执行顺序

```text
SPEC → RED → GREEN → REFACTOR → VERIFY
```

## Step 1 — Spec Review

冻结 Input、Output、State、Transaction、Event、Error、Security 和 Acceptance Scenarios。

## Step 2 — Domain RED

先写：

```text
TicketCreationTest
TicketValueObjectTest
TicketCreatedDomainEventTest
```

## Step 3 — Domain GREEN

实现最小：

```text
Ticket
TicketId
DisplayTicketId
RequesterId
TicketTitle
TicketDescription
TicketStatus
TicketSource
TicketCreatedDomainEvent
```

## Step 4 — Application RED

先写：

```text
CreateTicketApplicationServiceTest
```

验证 Authorization、Idempotency、ID Generation、History、Audit、Outbox 和 Result。

## Step 5 — Application GREEN

实现：

```text
CreateTicketUseCase
CreateTicketCommand
CreateTicketResult
CreateTicketApplicationService
```

以及所需 Ports。

## Step 6 — Persistence RED

先写：

```text
CreateTicketPersistenceIT
CreateTicketAtomicityIT
CreateTicketIdempotencyIT
```

## Step 7 — Persistence GREEN

实现 Flyway、JPA Entity、Mapper 和 Adapter。

## Step 8 — API RED

先写：

```text
CreateTicketControllerTest
CreateTicketSecurityTest
CreateTicketApiContractTest
```

## Step 9 — API GREEN

实现：

```text
PublicTicketController
CreateTicketRequest
CreateTicketResponse
CreateTicketDtoMapper
```

## Step 10 — Event Contract RED

先写：

```text
TicketCreatedEventContractTest
```

## Step 11 — Event GREEN

实现 Domain Event → Integration Event → Outbox JSON。

## Step 12 — Refactor

消除重复、强化 Value Object、检查 Package Direction、Transaction Boundary 和 Secret Redaction。

## Step 13 — Verify

运行 Unit、Application、Controller、Security、PostgreSQL Integration、Atomicity、Idempotency、Event Contract、ArchUnit 和 Coverage。

---

# 21. 测试清单

## Domain

```text
TicketCreationTest
TicketValueObjectTest
TicketCreatedDomainEventTest
```

## Application

```text
CreateTicketApplicationServiceTest
```

## API

```text
CreateTicketControllerTest
CreateTicketValidationTest
CreateTicketSecurityTest
CreateTicketErrorContractTest
```

## Persistence

```text
CreateTicketPersistenceIT
TicketCreationConstraintIT
FlywayTicketCreationMigrationIT
```

## Transaction

```text
CreateTicketAtomicityIT
```

覆盖 Ticket、History、Audit、Outbox 和 Idempotency Completion Failure。

## Idempotency

```text
CreateTicketIdempotencyIT
CreateTicketConcurrentIdempotencyIT
```

## Event

```text
TicketCreatedEventContractTest
TicketCreatedEventRedactionTest
```

## Architecture / Observability

```text
LayerDependencyTest
CreateTicketTelemetryTest
```

---

# 22. Acceptance Scenarios

```gherkin
Feature: Create Ticket

  Scenario: Create a valid Ticket
    Given an authenticated employee with tickets:create scope
    And a unique Idempotency-Key
    When the employee submits a valid Ticket request
    Then the response status is 201
    And exactly one Ticket is created
    And the Ticket status is NEW
    And one initial status history record exists
    And one required audit record exists
    And one ticket.created.v1 Outbox record exists

  Scenario: Replay the same request
    Given a completed Create Ticket request
    When the same actor repeats the same payload with the same Idempotency-Key
    Then the stored response is returned
    And no second Ticket is created

  Scenario: Reuse the key with a different payload
    Given a completed Create Ticket request
    When the same actor submits a different payload with the same Idempotency-Key
    Then the request is rejected
    And the error code is IDEMPOTENCY_KEY_REUSED

  Scenario: Reject requester identity injection
    Given an authenticated employee
    When the request body includes a requesterId
    Then the request is rejected or the field is disallowed by contract
    And the authenticated actor remains the only requester identity source

  Scenario: Roll back when Outbox insert fails
    Given a valid Create Ticket request
    And the Outbox insert fails
    When the command is executed
    Then no Ticket remains committed
    And no status history remains committed
    And no successful idempotency response is stored
```

---

# 23. 实施任务

```text
P01-T01 Review SPEC-TW-001
P01-T02 Add Domain RED Tests
P01-T03 Implement Domain Creation
P01-T04 Add Application RED Tests
P01-T05 Implement Application Service and Ports
P01-T06 Add Flyway Migrations
P01-T07 Implement Persistence Adapters
P01-T08 Add Persistence and Atomicity Tests
P01-T09 Add API and Security Tests
P01-T10 Implement Create Ticket API
P01-T11 Add Event Contract Test
P01-T12 Implement Outbox Event Mapping
P01-T13 Add Telemetry and Redaction
P01-T14 Update Traceability
P01-T15 Update Service README
```

---

# 24. 推荐 Pull Request 划分

## PR 1 — Spec and Domain

```text
docs(spec): define SPEC-TW-001 create ticket
test(ticket): add failing ticket creation domain tests
feat(ticket): implement ticket creation domain model
```

## PR 2 — Persistence and Transaction

```text
test(persistence): add create ticket integration tests
feat(database): add ticket creation migrations
feat(persistence): implement ticket creation adapters
test(transaction): verify create ticket atomicity
```

## PR 3 — API, Security and Contract

```text
test(api): add create ticket controller and security tests
feat(api): implement create ticket endpoint
test(contract): add ticket.created.v1 contract tests
feat(outbox): persist ticket.created.v1
```

## PR 4 — Hardening

```text
feat(observability): add create ticket telemetry
test(idempotency): add concurrent replay tests
docs(traceability): complete SPEC-TW-001 mapping
```

---

# 25. Deliverables

文档：

```text
phase-01-create-ticket_CN.md
phase-01-create-ticket_EN.md
SPEC-TW-001-create-ticket/spec_CN.md
SPEC-TW-001-create-ticket/spec_EN.md
acceptance.feature
traceability-matrix.yaml
```

代码：

```text
Ticket Domain Creation
CreateTicketApplicationService
Create Ticket API
Flyway Migrations
Persistence Adapters
Audit
Outbox
Idempotency
Telemetry
```

测试：

```text
Domain
Application
Controller
Security
Persistence
Atomicity
Idempotency
Event Contract
Architecture
Observability
```

---

# 26. 风险与处理

## Scope 过大

只实现 Create Ticket；Query 和 Message 留在 Phase 02；RabbitMQ Consumer 不进入本阶段。

## 一次创建全部未来表

只创建本 Slice 需要的表。

## Controller 包含业务逻辑

Controller 只负责 HTTP Mapping；业务放入 Domain / Application。

## JPA Entity 作为 API DTO

API DTO、Domain 和 Persistence Entity 必须分离。

## 事务内直接发布 RabbitMQ

只写 Outbox；Publisher 在事务外运行。

## 延后 Idempotency

Create Ticket 必须在 Phase 01 实现 Idempotency。

## 为测试放行所有 Security

Mock JWT 只提供认证上下文，Authorization 规则仍然执行。

## Outbox Payload 包含过多 PII

使用最小 Payload 和 Pseudonymous Requester Reference，并用 Redaction Test 强制检查。

---

# 27. Exit Criteria

## Spec

- `SPEC-TW-001-create-ticket` 已 Review。
- Acceptance Scenarios 已冻结。
- Design References 完整。

## Domain

- Ticket Creation Unit Test 通过。
- 初始状态只能是 `NEW`。
- Domain 无 Spring / JPA 依赖。
- Value Object Validation 通过。

## API

- `POST /api/v1/tickets` 返回 `201`。
- Request DTO 防止 Mass Assignment。
- Error Envelope 与 Contract 一致。
- `Idempotency-Key` 必需。
- Location 和 ETag 正确。

## Security

- 有效 Employee JWT 才能创建。
- 缺少 Scope 被拒绝。
- RequesterId 只能来自 Security Context。
- 不泄漏 JWT 或 Secret。

## Persistence

- Flyway 从空数据库成功执行。
- 使用真实 PostgreSQL。
- Constraint Test 通过。
- 不使用 H2。

## Transaction

- Ticket、History、Audit、Outbox 和 Idempotency 原子提交。
- 任意必要步骤失败全部 Rollback。

## Idempotency

- Same Key / Same Payload Replay。
- Same Key / Different Payload Conflict。
- Concurrent Duplicate 只创建一个 Ticket。

## Event

- `ticket.created.v1` Schema 通过。
- Outbox EventId 唯一。
- Payload 无 Secret。
- Aggregate Version 正确。

## Observability

- TraceId 可关联 API 和事务。
- Metrics 正确增加。
- Log 无高敏数据。
- Audit 完整。

## Quality

```text
./mvnw clean verify
```

通过，并且 ArchUnit、Coverage Gate、Secret Scan、CI 和 Docker Startup 全部通过。

## Documentation

- Service README 更新。
- Curl 示例可运行。
- Traceability Matrix 更新。
- Phase 02 边界清晰。

---

# 28. Exit Review Checklist

- [ ] Phase 00 已完成。
- [ ] SPEC-TW-001 已 Review。
- [ ] Domain RED 测试已先创建。
- [ ] Application RED 测试已先创建。
- [ ] API / Integration RED 测试已先创建。
- [ ] Ticket 初始状态固定为 `NEW`。
- [ ] RequesterId 来自 JWT。
- [ ] `Idempotency-Key` 已实现。
- [ ] Ticket、History、Audit、Outbox 和 Idempotency 原子提交。
- [ ] `ticket.created.v1` Contract 通过。
- [ ] PostgreSQL Testcontainer 通过。
- [ ] Concurrent Duplicate Test 通过。
- [ ] Security Test 通过。
- [ ] Telemetry Redaction Test 通过。
- [ ] ArchUnit 通过。
- [ ] `./mvnw clean verify` 通过。
- [ ] CI 通过。
- [ ] Traceability 更新。
- [ ] README 更新。

---

# 29. Phase 01 完成后

进入：

```text
Phase 02 — Ticket Query and Message Slice
```

下一批 Feature Specs：

```text
SPEC-TW-002-get-ticket
SPEC-TW-003-list-requester-tickets
SPEC-TW-004-add-ticket-message
SPEC-TW-005-support-queue-query
SPEC-TW-006-ticket-timeline
```

Phase 02 将复用 Phase 01 已建立的 Ticket Identity、Persistence、Security Principal、Error Envelope、Trace Context、Audit、API Versioning、Testcontainers 和 CI Quality Gate。

---

# 30. Definition of Done

Phase 01 完成意味着：

```text
Ticket Workflow 已经拥有第一个遵循 LLD、
由 Feature Spec 驱动、通过 TDD 实现，
并具备事务、幂等、安全、审计、Outbox、可观测性和自动化测试保护的完整业务 Vertical Slice。
```
