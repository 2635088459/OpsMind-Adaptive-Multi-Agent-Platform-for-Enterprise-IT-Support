# SPEC-TW-001 — Create Ticket

> **Spec ID：** SPEC-TW-001  
> **领域：** `02-ticket-workflow`  
> **功能：** Create Ticket  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **所属阶段：** Phase 01 — Create Ticket Vertical Slice  
> **主要 Actor：** EMPLOYEE  
> **API：** `POST /api/v1/tickets`  
> **Use Case：** UC-01  
> **状态转换：** SM-001 `Initial → NEW`  
> **发布事件：** PUB-001 `ticket.created` / `ticket.created.v1`  
> **代码目录：** `services/ticket-workflow-service/`

---

# 1. 目的

本规格定义 Employee 通过 OpsMind Employee Portal 创建一张新 IT Support Ticket 时必须满足的完整业务行为。

本规格是以下内容之间的可执行桥梁：

```text
Ticket Workflow LLD
→ Phase 01 Implementation Plan
→ Tests
→ Java / Spring Boot Implementation
```

开发者实现 Create Ticket 时，以本规格作为主要工作文档；当本规格引用具体设计 ID 时，再回查相应 LLD。

---

# 2. 业务结果

当一个拥有合法权限的 Employee 提交有效请求时，系统必须：

1. 验证 JWT 和 `tickets:create` Scope。
2. 从 JWT Subject 获取 RequesterId。
3. 验证并规范化请求。
4. 根据 Actor Scope 和 `Idempotency-Key` 执行幂等控制。
5. 创建初始状态为 `NEW` 的 Ticket。
6. 创建第一个 Resolution Cycle。
7. 创建第一个 SLA Cycle。
8. 创建初始 Status History。
9. 创建本地 Business Audit Record。
10. 创建 `ticket.created.v1` Outbox Record。
11. 保存稳定的幂等响应。
12. 在一个 PostgreSQL 本地事务中原子提交。
13. 返回 `201 Created`。

成功结果：

```text
One business intent
→ Exactly one Ticket
→ One active Resolution Cycle
→ One active SLA Cycle
→ One initial History
→ One local Audit Record
→ One ticket.created Outbox Event
→ One completed Idempotency Record
```

---

# 3. Spec 边界

## 3.1 本 Spec 包含

- Employee Portal 创建 Ticket
- API Validation
- Authentication
- Authorization
- Mass Assignment Protection
- Ticket Domain Creation
- Initial Resolution Cycle
- Initial SLA Cycle
- Initial Status History
- API Idempotency
- Local Business Audit
- Transactional Outbox Record
- Stable HTTP Response
- Error Contract
- Trace、Log 和 Metric
- Unit、Integration、Security、Contract 和 Concurrency Test

## 3.2 本 Spec 不包含

- IT Support 代用户创建 Ticket
- Service Account 创建 Ticket
- Email 自动建单
- Attachment 上传
- Ticket Query
- Ticket Message
- Agent Triage
- RabbitMQ Consumer
- Approval
- Tool Execution
- Verification
- Resolution
- Close / Reopen / Cancel
- 通用 Outbox Publisher 的完整可靠性实现
- 完整 Keycloak Realm 配置
- Dashboard 和 Alert

UC-01 虽然允许 `EMPLOYEE`、`IT_SUPPORT` 和 `AUTHORIZED_SERVICE`，但本 Spec 为 Phase 01 主动收窄到：

```text
EMPLOYEE through Public Employee API
```

其他 Actor 应使用独立 Feature Spec 或独立 API Contract，不能通过伪造 Employee Request 扩展本 Spec。

---

# 4. 设计引用

## 4.1 核心映射

| 类型 | 设计引用 |
|---|---|
| Use Case | UC-01 Create Ticket |
| API | API-001 Create Ticket |
| State Transition | SM-001 Initial → NEW |
| Published Event | PUB-001 ticket.created |
| Domain Model | Ticket、TicketResolutionCycle、TicketSla |
| Transaction | Document 08 Create Ticket Transaction |
| Idempotency | Document 09 HTTP Command Idempotency |
| Security | Document 11 Employee + `tickets:create` |
| Observability | Document 12 Create / HTTP / Audit Metrics |
| Package Design | Document 13 `CreateTicketApplicationService` |
| Testing | Document 14 Create、Atomicity、Idempotency Tests |

## 4.2 Business Invariants

本 Spec 直接应用：

```text
BI-001  Unique Ticket ID
BI-002  Unique Display ID
BI-003  Requester required and immutable
BI-004  Created time required and immutable
BI-005  Valid Title
BI-006  Valid Initial Description
BI-007  Allowed ApplicationCode
BI-008  Valid Category/Subcategory relationship
BI-011  Status change writes History
BI-080  At most one active SLA cycle
BI-081  SLA deadline cannot be before Ticket creation
BI-082  SLA state follows Ticket policy
BI-085  Create Ticket supports Idempotency-Key
BI-087  Replay returns stable result
BI-088  Same key cannot represent different payload
BI-095  Ticket, History and Outbox commit atomically
BI-097  No external system call inside DB transaction
BI-101  Secret cannot enter Ticket Domain
BI-102  Integration Event minimizes PII
BI-104  Audit is append-only
BI-105  Status History is append-only
BI-108  Command propagates Trace Context
BI-109  Metrics labels exclude PII and high-cardinality IDs
BI-110  Telemetry export failure does not block business commit
```

## 4.3 设计同步决定

本 Spec 对现有 LLD 中的分散要求做出以下明确组合：

1. **Initial Resolution Cycle 和 Initial SLA Cycle 是必须项。**  
   UC-01、SM-001、Document 07 和 Document 08 都要求 Create Ticket 创建这两项。

2. **Local Business Audit Record 是必须项。**  
   Document 12 将 Ticket Created 定义为 Business Audit；Phase 01 将其设为本地强一致记录。编码合并前应把该要求同步回 Document 07 / 08 的 Create Ticket Transaction。

3. **附件不在本 Spec。**  
   Package Design 中出现的 `attachmentIds` 不属于 API-001 当前 Contract，因此 Phase 01 DTO 不包含附件字段。

4. **Outbox Record 必须完成，RabbitMQ Publish 不作为本 Spec 的同步成功条件。**  
   API 只等待数据库 Commit，不等待 Broker Publisher Confirm。

5. **401 错误码使用 `UNAUTHENTICATED`。**  
   该名称与 Document 10 的标准错误语义一致；API Contract 中的 `UNAUTHORIZED` 应在后续文档同步时修正。

---

# 5. Actor 与授权

## 5.1 Actor

```text
principalType = EMPLOYEE
```

## 5.2 Authentication

JWT 必须验证：

- Signature
- Issuer
- Audience
- Expiration
- Not Before
- Authorized Party
- Token Type
- Subject
- Environment

## 5.3 Authorization

Principal 必须拥有：

```text
tickets:create
```

缺少 Scope：

```text
HTTP 403
FORBIDDEN
```

## 5.4 Requester Identity

```text
requesterId = principal.subject
```

RequesterId 不得从以下位置获取：

- Request Body
- Query Parameter
- 自定义 Header
- Cookie
- 前端隐藏字段

## 5.5 Rate Limit

建议限制：

```text
10 requests / minute / user
```

超过限制：

```text
HTTP 429
RATE_LIMITED
```

---

# 6. HTTP Contract

## 6.1 Endpoint

```http
POST /api/v1/tickets
```

## 6.2 Required Headers

```http
Authorization: Bearer <JWT>
Idempotency-Key: <1-128 characters>
Content-Type: application/json
Accept: application/json
```

## 6.3 Optional Trace Headers

```http
traceparent: <W3C trace context>
X-Correlation-Id: <1-128 characters>
```

如果 `X-Correlation-Id` 缺失，服务生成新的 Correlation ID。

`Idempotency-Key` 不得直接作为 Correlation ID、Trace Attribute 或普通日志字段。

---

# 7. Request Schema

```json
{
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL"
}
```

## 7.1 字段规则

| 字段 | Required | 规则 | 分类 |
|---|---:|---|---|
| `title` | yes | trim 后 1–200；禁止控制字符 | SENSITIVE |
| `description` | yes | 1–10000；不能为空；显示前安全处理 | SENSITIVE |
| `applicationCode` | yes | `HOUSING_PORTAL` / `EMAIL` / `VPN` / `OTHER` | INTERNAL |
| `source` | yes | Employee MVP 只能是 `PORTAL` | INTERNAL |

## 7.2 禁止字段

Request Schema 使用：

```text
additionalProperties = false
```

以下字段出现时返回 `400 VALIDATION_ERROR`：

```text
ticketId
displayId
requesterId
status
priority
category
subcategory
assignedTeam
assignedAgent
workflowId
approvalId
resolutionCycleId
slaCycleId
createdAt
updatedAt
version
attachmentIds
```

这样可以防止 Mass Assignment 和 Requester Identity Injection。

---

# 8. 请求规范化

在计算 Idempotency Request Hash 前：

- Title 去除首尾空白。
- `applicationCode` 和 `source` 使用 Contract 中的规范枚举值。
- JSON Object Key 按字典序排序。
- UTF-8 编码。
- `null` 与字段缺失保持不同语义。
- Array 保持原顺序。
- 不自动修改 Description 的业务内容。
- 换行使用稳定规范表示。
- Unknown Field 在 Hash 前已被拒绝。

参与 Hash：

```text
HTTP method
normalized route template
actor scope
canonical JSON body
selected semantic headers
```

不参与 Hash：

```text
JWT
traceparent
X-Correlation-Id
request time
header order
JSON field order
```

Hash：

```text
SHA-256(canonical request)
```

---

# 9. Domain 创建行为

推荐 Domain Factory：

```java
Ticket.create(
    TicketId id,
    TicketDisplayId displayId,
    RequesterId requesterId,
    TicketTitle title,
    TicketDescription description,
    ApplicationCode application,
    TicketSource source,
    Instant now
)
```

创建结果必须是：

```text
status = NEW
priority = UNASSIGNED
category = null
subcategory = null
currentAssignment = null
activeWorkflowId = null
pendingAction = null
resolution = null
resolvedAt = null
closedAt = null
cancelledAt = null
createdAt = now
updatedAt = now
version = 0
```

Domain 产生：

```text
TicketCreated
```

Domain Event 至少包含：

```text
ticketId
displayId
requesterId
applicationCode
source
createdAt
```

Domain Event 不包含 RabbitMQ Routing Key 或 JSON 序列化逻辑。

---

# 10. ID 生成

## 10.1 TicketId

- 服务端生成。
- 使用 UUIDv7 或项目批准的有序 UUID。
- 全局唯一。
- 不使用数据库自增 ID 作为跨服务 ID。

## 10.2 TicketDisplayId

格式：

```text
INC-<number>
```

例如：

```text
INC-2048
```

要求：

- Ticket Workflow 范围内唯一。
- 创建后不可修改。
- 发生唯一约束冲突时允许有限次数重新生成。
- 有限重试耗尽后返回安全的 `INTERNAL_ERROR`，全部回滚。

## 10.3 其他 ID

服务端生成：

```text
resolutionCycleId
slaCycleId
historyId
auditId
eventId
outboxId
idempotencyRecordId
commandId
```

---

# 11. Initial Resolution Cycle

必须创建：

```text
cycleNumber = 1
cycleStatus = ACTIVE
workflowId = null
openedAt = ticket.createdAt
resolvedAt = null
closedAt = null
```

Ticket：

```text
currentResolutionCycleId = resolutionCycleId
```

约束：

- 同一 Ticket 同一时间最多一个 `ACTIVE` Resolution Cycle。
- Initial Cycle 与 Ticket 在同一事务创建。
- Initial Cycle 创建失败时 Ticket 不得提交。

---

# 12. Initial SLA Cycle

必须创建：

```text
cycleNumber = 1
status = ACTIVE
ticketId = created Ticket
resolutionCycleId = initial Resolution Cycle
policyId = resolved SLA policy
createdAt = ticket.createdAt
updatedAt = ticket.createdAt
version = 0
```

SLA Policy：

- 必须来自本服务批准的本地配置或本地数据库。
- Create Ticket 事务中不得调用远程 SLA Service。
- 必须保证 Deadline 不早于 Ticket 创建时间。
- 如果必需的默认 Policy 缺失，视为配置错误，返回 `INTERNAL_ERROR` 并全部回滚。

---

# 13. Initial Status History

必须写入一条 Append-only Record：

```text
fromStatus = null
toStatus = NEW
transitionId = SM-001
reasonCode = TICKET_CREATED
actorType = EMPLOYEE
actorId = principal.subject
sourceCommandId = commandId
sourceEventId = null
workflowId = null
aggregateVersion = 0
occurredAt = ticket.createdAt
```

约束：

- `(ticketId, aggregateVersion)` 唯一。
- 应用账号不执行普通 UPDATE / DELETE。
- History 插入失败时全部回滚。

---

# 14. Transaction Boundary

Application Entry Point：

```text
CreateTicketApplicationService.create(...)
```

公共方法使用：

```text
@Transactional
```

事务顺序：

```text
BEGIN

1. Reserve idempotency_record as IN_PROGRESS
2. Generate Ticket and related IDs
3. Create Ticket Aggregate
4. Resolve local SLA Policy
5. Insert ticket.tickets
6. Insert ticket.ticket_resolution_cycles
7. Insert ticket.ticket_sla_cycles
8. Insert ticket.ticket_status_history
9. Insert local ticket.audit_records
10. Insert ticket.outbox_events for ticket.created
11. Complete idempotency_record with stable response
12. COMMIT
```

任一步失败：

```text
ROLLBACK ALL
```

事务中禁止：

- RabbitMQ Publish
- Publisher Confirm Wait
- Agent Runtime Call
- Keycloak Admin Call
- Approval Service Call
- Tool Gateway Call
- External HTTP
- LangSmith Network Call
- OTel Export Wait

Telemetry Export 发生在业务关键路径之外；Export 失败不回滚已成功的业务事务。

---

# 15. Idempotency

## 15.1 Actor Scope

```text
user:{principal.subject}:createTicket
```

唯一约束：

```text
actor_scope + idempotency_key
```

## 15.2 TTL

```text
24 hours
```

## 15.3 Stale Threshold

```text
5 minutes
```

## 15.4 行为矩阵

| Existing Record | Payload | 结果 |
|---|---|---|
| none | valid | Reserve `IN_PROGRESS`，执行 |
| `COMPLETED` | same hash | 返回原结果 |
| `COMPLETED` | different hash | `409 IDEMPOTENCY_KEY_REUSED` |
| fresh `IN_PROGRESS` | same hash | `409 REQUEST_IN_PROGRESS` + `Retry-After: 1` |
| stale `IN_PROGRESS` | same hash | 进入 Reconciliation，不创建第二张 Ticket |
| `FAILED_RETRYABLE` | same hash | 受控重新 Reserve |
| `FAILED_FINAL` | same hash | 返回已保存的最终错误 |

## 15.5 Replay Response

Replay 必须返回：

- 原始 HTTP Status：`201`
- 相同 TicketId
- 相同 DisplayId
- 相同主要 Response Body
- 相同 Location
- 相同 ETag

可新增：

```http
Idempotency-Replayed: true
```

Replay 不得创建：

- 新 Ticket
- 新 Resolution Cycle
- 新 SLA Cycle
- 新 History
- 新 Audit Record
- 新 Outbox Event

## 15.6 并发目标

```text
100 concurrent requests
same actor
same Idempotency-Key
same payload
→ exactly one Ticket
```

其他请求只能：

- 返回同一结果；或
- 在首个请求执行期间返回 `REQUEST_IN_PROGRESS`。

---

# 16. Persistence Requirements

Phase 01 至少需要：

```text
ticket.tickets
ticket.ticket_resolution_cycles
ticket.ticket_sla_cycles
ticket.ticket_status_history
ticket.audit_records
ticket.outbox_events
ticket.idempotency_records
```

## 16.1 `ticket.tickets`

关键初始字段：

```text
requester_id = principal.subject
priority = UNASSIGNED
status = NEW
current_resolution_cycle_id = initial cycle
active_workflow_id = null
version = 0
created_by_type = EMPLOYEE
created_by_id = principal.subject
```

## 16.2 Persistence Separation

必须保持：

```text
API DTO
≠ Domain Object
≠ JPA Entity
```

禁止：

- Domain Class 使用 JPA Annotation。
- Controller 直接操作 Spring Data Repository。
- Application Service 依赖 Repository Implementation。
- JPA Entity 作为 HTTP Response。

---

# 17. Integration Event

## 17.1 Event Identity

```text
eventType = ticket.created
eventVersion = 1.0
routingKey = ticket.created.v1
producer = ticket-workflow-service
aggregateType = Ticket
aggregateId = ticketId
aggregateVersion = 0
sequence = 0
partitionKey = ticketId
dataClassification = INTERNAL
```

## 17.2 Payload

```json
{
  "displayId": "INC-2048",
  "requesterIdHash": "hmac-sha256:<hex>",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "initialStatus": "NEW",
  "createdAt": "2026-07-23T16:30:00Z"
}
```

`requesterIdHash` 使用：

```text
HMAC-SHA-256(service-controlled key, requesterId)
```

普通无 Salt SHA-256 不允许用于低熵 Requester ID。

## 17.3 禁止内容

Event Envelope 和 Payload 不得包含：

```text
title
description
requester email
raw requesterId
password
access token
refresh token
API key
session cookie
private key
authorization header
Idempotency-Key
```

## 17.4 Outbox Requirement

Outbox Record 必须与 Ticket 在同一事务提交。

本 Spec 的同步 API 成功条件是：

```text
Outbox Record committed
```

不是：

```text
RabbitMQ publish confirmed
```

Outbox Publisher 最终负责 At-least-once 发布；Consumer 负责 Idempotency。

---

# 18. Business Audit

必须写入本地 Append-only Audit Record：

```text
auditType = BUSINESS_ACTION
action = TICKET_CREATED
decision = ALLOWED
actorType = EMPLOYEE
actorId = principal.subject
clientId = JWT authorized party / client ID
resourceType = TICKET
resourceId = ticketId
displayId = displayId
ticketStatusBefore = null
ticketStatusAfter = NEW
traceId = current trace
commandId = commandId
outcome = SUCCESS
dataClassification = SENSITIVE
occurredAt = ticket.createdAt
```

Audit 不保存：

- Title
- Description
- JWT
- Idempotency-Key
- Request Body
- Secret

Audit Record：

- Append-only。
- 不允许普通 UPDATE / DELETE。
- 插入失败时 Create Ticket Fail Closed 并全部回滚。

Authentication / Authorization Failure 的 Security Audit 可以在业务事务之外记录，但不得泄漏 Token。

---

# 19. Observability

## 19.1 Trace

至少包含：

```text
HTTP POST /api/v1/tickets
CreateTicketUseCase
ticket.create
ticket.create.transaction
db.ticket.insert
db.resolution_cycle.insert
db.sla_cycle.insert
db.history.insert
db.audit.insert
db.outbox.insert
db.idempotency.complete
```

Trace Attribute 允许：

```text
service.name
operation
result
status
application_code
source
replayed
```

禁止：

```text
ticketId as metric label
requesterId
title
description
JWT
Idempotency-Key
```

TicketId 可以作为受控 Trace Attribute，但不得作为 Prometheus Label。

## 19.2 Metrics

至少：

```text
opsmind_ticket_http_requests_total
opsmind_ticket_http_request_duration_seconds
opsmind_ticket_http_errors_total
opsmind_ticket_rate_limited_total
opsmind_ticket_authorization_denied_total
opsmind_ticket_idempotency_replay_total
opsmind_ticket_created_total
```

有限集合 Label：

```text
route
method
status_class
operation
result
application_code
source
```

## 19.3 Logging

Structured Log 允许：

```text
traceId
correlationId
operation
safe error code
result
duration
```

禁止记录原始 Request Body。

---

# 20. Success Response

```http
HTTP/1.1 201 Created
Location: /api/v1/tickets/{ticketId}
ETag: "0"
Content-Type: application/json
```

```json
{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "status": "NEW",
  "createdAt": "2026-07-23T16:30:00Z",
  "version": 0
}
```

Response Body 必须可安全保存进 `idempotency_records.response_body`。

---

# 21. Error Contract

统一 Envelope：

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The request is invalid.",
    "traceId": "8f03...",
    "correlationId": "corr-...",
    "details": {}
  }
}
```

## 21.1 Error Matrix

| 场景 | HTTP | Error Code |
|---|---:|---|
| Invalid JSON / invalid field | 400 | `VALIDATION_ERROR` |
| Missing Idempotency-Key | 400 | `VALIDATION_ERROR` |
| Missing / invalid / expired JWT | 401 | `UNAUTHENTICATED` |
| Missing `tickets:create` | 403 | `FORBIDDEN` |
| Same key, different payload | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Same key still processing | 409 | `REQUEST_IN_PROGRESS` |
| Unique / integrity conflict not safely recoverable | 409 or 500 | `DATA_INTEGRITY_CONFLICT` |
| Rate limit exceeded | 429 | `RATE_LIMITED` |
| Local DB unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected internal failure | 500 | `INTERNAL_ERROR` |

## 21.2 安全要求

Error Response 不得包含：

- Stack Trace
- SQL
- Table Name
- Constraint Name
- Internal Exception Class
- Raw JWT
- Password
- Database Connection String

---

# 22. Acceptance Scenarios

完整可执行场景位于：

```text
acceptance.feature
```

最低必须覆盖：

1. Valid Employee creates one Ticket。
2. Initial Ticket、Resolution Cycle、SLA Cycle 和 History 正确。
3. Same Key + Same Payload 返回原结果。
4. Same Key + Different Payload 被拒绝。
5. Fresh `IN_PROGRESS` 返回 `REQUEST_IN_PROGRESS`。
6. RequesterId Injection 被拒绝。
7. Missing Scope 被拒绝。
8. Missing Idempotency-Key 被拒绝。
9. SLA Insert Failure 全部回滚。
10. Audit Insert Failure 全部回滚。
11. Outbox Insert Failure 全部回滚。
12. Concurrent Duplicate 只创建一张 Ticket。
13. Event 不包含 Description、Requester Email 或 Secret。

---

# 23. Tests First

## 23.1 Domain RED

```text
TicketCreationTest
TicketTitleTest
TicketDescriptionTest
ApplicationCodeTest
TicketCreatedDomainEventTest
```

## 23.2 Application RED

```text
CreateTicketApplicationServiceTest
CreateTicketAuthorizationTest
CreateTicketIdempotencyReplayTest
```

## 23.3 API RED

```text
CreateTicketControllerTest
CreateTicketValidationTest
CreateTicketSecurityTest
CreateTicketMassAssignmentTest
CreateTicketErrorContractTest
```

## 23.4 Persistence RED

```text
FlywayCreateTicketMigrationIT
CreateTicketPersistenceIT
CreateInitialResolutionCycleIT
CreateInitialSlaCycleIT
TicketCreationConstraintIT
```

## 23.5 Transaction RED

```text
CreateTicketAtomicityIT
```

必须注入并验证：

```text
FAIL_TICKET_INSERT
FAIL_RESOLUTION_CYCLE_INSERT
FAIL_SLA_CYCLE_INSERT
FAIL_HISTORY_INSERT
FAIL_AUDIT_INSERT
FAIL_OUTBOX_INSERT
FAIL_IDEMPOTENCY_COMPLETION
```

## 23.6 Idempotency / Concurrency RED

```text
CreateTicketIdempotencyIT
CreateTicketConcurrentIdempotencyIT
CreateTicketStaleIdempotencyIT
```

## 23.7 Contract / Privacy RED

```text
TicketCreatedEventContractTest
TicketCreatedEventRedactionTest
CreateTicketAuditRedactionTest
```

## 23.8 Architecture / Telemetry

```text
LayerDependencyTest
CreateTicketTelemetryTest
```

---

# 24. Package 与 Class Mapping

建议实现：

```text
ticket.api.publicapi
├── PublicTicketController
├── CreateTicketRequest
├── CreateTicketResponse
└── PublicTicketApiMapper

ticket.application.port.in
└── CreateTicketUseCase

ticket.application.command
├── CreateTicketCommand
└── CreateTicketResult

ticket.application.service
└── CreateTicketApplicationService

ticket.application.port.out
├── TicketRepository
├── TicketResolutionCycleRepository
├── TicketSlaRepository
├── TicketHistoryWriter
├── AuditRecordPort
├── OutboxEventRepository
├── IdempotencyRepository
├── TicketIdGenerator
├── TicketDisplayIdGenerator
├── ClockPort
└── SlaPolicyResolver

ticket.domain
├── Ticket
├── TicketId
├── TicketDisplayId
├── RequesterId
├── TicketTitle
├── TicketDescription
├── ApplicationCode
├── TicketSource
├── TicketStatus
├── TicketPriority
└── TicketCreated

ticket.infrastructure.persistence
├── TicketJpaEntity
├── TicketResolutionCycleJpaEntity
├── TicketSlaCycleJpaEntity
├── TicketStatusHistoryJpaEntity
├── AuditRecordJpaEntity
├── OutboxEventJpaEntity
├── IdempotencyRecordJpaEntity
├── TicketPersistenceMapper
└── Persistence Adapters
```

Application Service 依赖 Port，不依赖 Spring Data Repository Implementation。

---

# 25. Traceability

本 Spec 的 Traceability 条目位于：

```text
traceability-entry.yaml
```

实现完成后，应将其合并进：

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

每个最终类名和测试名必须与真实代码同步，不能让 Traceability 长期保留计划名称。

---

# 26. Non-functional Requirements

## Reliability

- 所有本地强一致 Record 原子提交。
- API 不依赖 RabbitMQ 当下可用性。
- Display ID 冲突只允许有限重试。
- 幂等并发不产生重复资源。

## Performance

Phase 01 目标：

```text
Create command p95 < 800 ms
Create command p99 < 2 s
```

不包含：

- 外部系统网络调用
- RabbitMQ Publisher Confirm
- Agent Runtime

## Availability

Ticket API 建议 SLO：

```text
99.5%
```

## Data Protection

- Title / Description 视为 SENSITIVE。
- SECRET 被拒绝进入 Domain、Audit、Event、Log 和 Trace。
- Event 最小化 PII。

---

# 27. Definition of Done

只有全部满足时，`SPEC-TW-001` 才完成：

- [ ] Spec 已 Review 并冻结。
- [ ] Phase 00 已完成。
- [ ] API-001 Contract 通过。
- [ ] SM-001 通过。
- [ ] BI-001–008 通过。
- [ ] Initial Resolution Cycle 正确创建。
- [ ] Initial SLA Cycle 正确创建。
- [ ] Initial Status History 正确创建。
- [ ] Local Business Audit 正确创建。
- [ ] `ticket.created.v1` Outbox Record 正确创建。
- [ ] Idempotency Replay 返回稳定结果。
- [ ] Same Key + Different Payload 被拒绝。
- [ ] 100 并发重复请求只创建一个 Ticket。
- [ ] 任意必要 Insert 失败全部回滚。
- [ ] RequesterId 只来自 JWT。
- [ ] Mass Assignment 被阻止。
- [ ] Event / Audit / Log Redaction Test 通过。
- [ ] Domain 无 Spring / JPA 依赖。
- [ ] PostgreSQL Testcontainer 测试通过。
- [ ] ArchUnit 通过。
- [ ] `./mvnw clean verify` 通过。
- [ ] CI 通过。
- [ ] Traceability 已更新。
- [ ] README 的 Curl 示例可以执行。

---

# 28. 实现后业务保证

当本 Spec 完成时，OpsMind 可以可靠保证：

```text
一次合法的 Employee 建单意图，
即使发生重复点击、网络重试、并发请求或局部数据库失败，
也只会产生一张完整、可审计、可继续编排的 Ticket。
```
