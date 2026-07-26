# SPEC-TW-002 — Get Ticket

> **Spec ID：** SPEC-TW-002  
> **领域：** `02-ticket-workflow`  
> **功能：** Get Ticket  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **所属阶段：** Phase 02 — Ticket Query and Message Slice  
> **主要 Actors：** EMPLOYEE、IT_SUPPORT、IT_ADMIN、IT_MANAGER、AUDITOR  
> **API：** `GET /api/v1/tickets/{ticketId}`  
> **Use Case：** UC-02 Get Ticket  
> **API Contract：** API-002 Get Ticket  
> **代码目录：** `services/ticket-workflow-service/`

---

# 1. 目的

本规格定义授权 Actor 通过 Ticket ID 读取单张 Ticket 当前详情时必须满足的完整行为。

它将以下设计转化为一个可测试、可实现的 Vertical Slice：

```text
Ticket Read Model
+ Resource Ownership
+ Support Scope Authorization
+ Field Visibility
+ Conditional GET
+ Sensitive Read Audit
+ Query Observability
```

开发时以本规格为主要工作文档；本规格引用的详细架构、安全、数据和测试规则仍以 Ticket Workflow LLD 为 Source of Truth。

---

# 2. 业务结果

成功执行后：

```text
Authorized Actor
→ GET /api/v1/tickets/{ticketId}
→ Authentication
→ Coarse-grained Scope Check
→ Resource-level Authorization in Query
→ Actor-specific Projection
→ Optional Sensitive Read Audit
→ HTTP 200 + ETag
```

系统必须保证：

- Employee 只能查看自己创建的 Ticket。
- Support 只能查看其授权支持范围内的 Ticket。
- 返回字段由 Actor 类型和 Scope 决定。
- Employee 永远看不到 Internal 字段。
- 未授权资源访问不会泄漏 Ticket 是否存在。
- Query 不修改 Ticket、Version、`updatedAt` 或任何业务状态。
- Query 不重建完整 Ticket Aggregate。
- Query 使用真实 PostgreSQL Projection。
- 支持 `If-None-Match` 条件读取。
- Support 敏感详情读取满足审计要求。

---

# 3. Spec 边界

## 3.1 本 Spec 包含

- 通过内部 `ticketId` 获取单张 Ticket
- JWT Authentication
- Scope Authorization
- Employee Resource Ownership
- Support Resource Scope
- Employee View
- Support View
- Auditor View Policy Hook
- SQL-level authorization filtering
- Field-level visibility
- ETag
- `If-None-Match`
- `304 Not Modified`
- Safe `404`
- Sensitive Read Audit
- Query telemetry
- JSON Schema validation
- Unit、Controller、Security 和 PostgreSQL Integration Tests

## 3.2 本 Spec 不包含

- 通过 Display ID 搜索
- Ticket 列表
- Support Queue 列表
- Ticket Timeline
- Ticket Messages 列表
- 添加消息
- Attachment 下载
- Ticket 状态修改
- Assignment 修改
- Triage
- Approval
- Tool Execution
- Verification
- Full-text Search
- Semantic Search
- WebSocket 更新
- Response Cache
- GraphQL

---

# 4. 设计引用

## 4.1 核心映射

| 类型 | 设计引用 |
|---|---|
| Use Case | UC-02 Get Ticket |
| API | API-002 Get Ticket |
| Domain | Ticket Identity、Requester、Status、Priority、Assignment、SLA |
| Data Model | `ticket.tickets`、Resolution Cycle、SLA Cycle |
| Security | Resource Ownership、Support Queue Scope、Field Visibility |
| Error Handling | Not Found、Forbidden、Invalid ID、Safe Error Envelope |
| Observability | Ticket Read、Authorization Denied、Sensitive Read Audit |
| Package Design | Query Service、Query Port、JDBC Projection Adapter |
| Testing | Query、Security、Visibility、PostgreSQL Integration |

## 4.2 适用的业务与安全约束

本 Spec 至少保证：

```text
TicketId 必须唯一且不可变
RequesterId 必须不可变
Query 不产生状态转换
Employee 只能读取自己的资源
Support 只能读取授权范围内的资源
Internal 字段不能暴露给 Employee
资源级未授权访问隐藏为 Not Found
Audit Record 为 Append-only
Metrics Label 不包含 TicketId 或 RequesterId
Log / Trace 不包含 Description、JWT 或 Secret
```

最终 Traceability 中应替换为 LLD 中冻结的精确 BI / SEC / OBS 编号。

---

# 5. Actor 和 View 决策

服务端根据 Principal 决定 View。

客户端不得通过以下方式选择更高权限 View：

```text
?view=support
X-View-Type: INTERNAL
role in request
scope in request
```

View Resolution：

```text
EMPLOYEE
→ EMPLOYEE_VIEW

IT_SUPPORT / IT_ADMIN / IT_MANAGER
→ SUPPORT_VIEW when resource scope allows

AUDITOR
→ AUDITOR_VIEW when approved audit scope allows
```

Actor 同时拥有多个角色时，使用明确的服务端 Policy，不能简单返回字段最多的 View。

---

# 6. HTTP Contract

## 6.1 Endpoint

```http
GET /api/v1/tickets/{ticketId}
```

## 6.2 Path Parameter

```text
ticketId
```

规则：

- UUID 字符串。
- MVP 使用 UUIDv7，但 API Validation 接受规范 UUID 表示。
- 空值、非法 UUID、额外路径字符返回 `400 VALIDATION_ERROR`。
- 本 Spec 不通过 Display ID 查询。

## 6.3 Required Headers

```http
Authorization: Bearer <JWT>
Accept: application/json
```

## 6.4 Optional Headers

```http
If-None-Match: "<version>"
traceparent: <W3C trace context>
X-Correlation-Id: <1-128 characters>
```

## 6.5 Response Headers

成功：

```http
ETag: "<ticket-version>"
Cache-Control: private, no-store
Vary: Authorization
Content-Type: application/json
```

条件命中：

```http
HTTP 304 Not Modified
ETag: "<ticket-version>"
Cache-Control: private, no-store
Vary: Authorization
```

`304` 不返回 Response Body。

---

# 7. Authentication

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

失败：

```text
HTTP 401
UNAUTHENTICATED
```

Error Response 不区分 Token 不存在、过期或签名错误的内部细节。

---

# 8. Coarse-grained Authorization

## 8.1 Employee

要求批准的读取 Scope，例如：

```text
tickets:read:self
```

## 8.2 Support

要求批准的读取 Scope，例如：

```text
tickets:read:queue
```

## 8.3 Auditor

要求批准的读取 Scope，例如：

```text
tickets:audit:read
```

缺少全局读取 Scope：

```text
HTTP 403
FORBIDDEN
```

拥有全局 Scope 但资源不在授权范围：

```text
HTTP 404
TICKET_NOT_FOUND
```

---

# 9. Resource-level Authorization

资源级授权必须尽量下推到 SQL。

## 9.1 Employee Query Predicate

```sql
WHERE ticket_id = :ticketId
  AND requester_id = :principalSubject
```

不能：

```text
先按 TicketId 读取
→ 再在 Java 内判断 Requester
```

这会增加误用和数据泄漏风险。

## 9.2 Support Query Predicate

至少包含：

```text
ticketId
+
allowed application codes
+
allowed support teams / queues
+
tenant / region when applicable
+
sensitivity policy
```

Support Scope 来源必须是可信 Security Context 或批准的本地 Authorization Projection。

本 Spec 的同步 Query 不调用远程 Policy Service。

## 9.3 Auditor Query Predicate

Auditor 只能读取 Policy 允许的字段和资源。

Auditor View 不能默认等同于 Support View。

---

# 10. Resource Hiding

以下情况统一返回：

```text
HTTP 404
TICKET_NOT_FOUND
```

- Ticket 实际不存在。
- Employee 请求他人的 Ticket。
- Support 请求授权范围外的 Ticket。
- Auditor 请求未批准资源。
- Ticket 已被租户隔离策略隐藏。

Response 不说明：

```text
Ticket exists but you do not have access
```

这样可以降低 Ticket ID 枚举风险。

---

# 11. Query Architecture

采用轻量 CQRS：

```text
Controller
→ GetTicketUseCase
→ GetTicketApplicationService
→ TicketQueryPort
→ JdbcTicketQueryAdapter
→ PostgreSQL Projection
→ Actor-specific DTO
```

Query Side 规则：

- 不加载完整 Ticket Aggregate。
- 不使用 JPA Lazy Graph 组合大型 Response。
- 不使用 JPA Entity 作为 Response。
- 使用明确 SQL 或 Spring JDBC Projection。
- SQL 返回 View 所需的最小字段。
- Employee 和 Support 可使用不同 SQL Projection。
- Query 使用参数化 SQL。
- 默认 Isolation 为 `READ COMMITTED`。
- Employee 自助读取使用只读事务或无显式事务。
- Query 不更新 `updatedAt`、Version 或 Last Activity。

---

# 12. Employee View

Employee Response Schema：

```text
schemas/employee-ticket-response.schema.json
```

建议字段：

```json
{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "status": "NEW",
  "priority": "UNASSIGNED",
  "createdAt": "2026-07-23T16:30:00Z",
  "updatedAt": "2026-07-23T16:30:00Z",
  "version": 0,
  "sla": {
    "state": "ACTIVE",
    "responseDueAt": "2026-07-23T20:30:00Z",
    "resolutionDueAt": "2026-07-24T16:30:00Z"
  },
  "links": {
    "self": "/api/v1/tickets/018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
    "timeline": "/api/v1/tickets/018f0f1e-7b31-7a00-8f42-31f9b25b1a91/timeline",
    "messages": "/api/v1/tickets/018f0f1e-7b31-7a00-8f42-31f9b25b1a91/messages"
  }
}
```

## 12.1 Employee View 允许

- TicketId
- DisplayId
- Title
- Description
- ApplicationCode
- Source
- Status
- Priority
- Public SLA Summary
- CreatedAt
- UpdatedAt
- Version
- Safe Links

## 12.2 Employee View 禁止

- Requester internal identifier
- Requester email
- Internal messages
- Internal notes
- Internal assignment IDs
- Support actor IDs
- Risk score
- Security flags
- Approval internals
- Tool request / execution details
- Verification internals
- Reconciliation metadata
- Audit metadata
- Active Workflow internal ID
- Secret or credential

---

# 13. Support View

Support Response Schema：

```text
schemas/support-ticket-response.schema.json
```

Support View 可以在 Employee View 基础上增加：

```text
requesterRef
assignment
resolutionCycle
sla internal summary
activeWorkflow summary
internal classification summary
```

只返回完成当前支持任务所需的最小字段。

示例：

```json
{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "status": "NEW",
  "priority": "UNASSIGNED",
  "requesterRef": "usr_7f2d8a",
  "assignment": {
    "teamId": null,
    "agentId": null,
    "queue": "HOUSING_PORTAL"
  },
  "resolutionCycle": {
    "cycleNumber": 1,
    "status": "ACTIVE"
  },
  "sla": {
    "state": "ACTIVE",
    "policyId": "SLA-STANDARD-P2",
    "responseDueAt": "2026-07-23T20:30:00Z",
    "resolutionDueAt": "2026-07-24T16:30:00Z"
  },
  "createdAt": "2026-07-23T16:30:00Z",
  "updatedAt": "2026-07-23T16:30:00Z",
  "version": 0
}
```

Support View 仍然禁止：

- Password
- Token
- Session Cookie
- API Key
- Tool Credential
- Requester secret
- Full Audit Record
- Unauthorized tenant / region fields
- Internal fields unrelated to current role

---

# 14. Auditor View

Auditor View 在本 Spec 中只定义 Policy Hook，不完整实现专用审计 API。

最低要求：

- Auditor 不能因拥有审计角色自动看到完整 Description。
- 字段由 `AUDITOR_VIEW` Policy 决定。
- 审计读取本身必须记录 Security Audit。
- 未批准的敏感字段必须省略或遮蔽。
- 后续可以拆分为独立 Auditor API Spec。

---

# 15. Conditional GET

## 15.1 ETag

```text
ETag = quoted decimal aggregate version
```

例如：

```http
ETag: "0"
```

## 15.2 If-None-Match

当：

```text
If-None-Match == current ETag
```

返回：

```text
HTTP 304 Not Modified
```

不返回 Body。

## 15.3 Authorization First

即使 ETag 匹配，也必须先完成：

- Authentication
- Scope Authorization
- Resource Authorization

不能通过 `304` 泄漏资源存在性或 Version。

## 15.4 Audit

Support / Auditor 的敏感条件读取仍视为一次访问，按 Policy 记录 Audit。

---

# 16. Sensitive Read Audit

## 16.1 Employee Self-read

普通 Employee 查看自己的 Ticket：

- 记录标准访问日志、Trace 和 Metric。
- 默认不创建高成本 Business Audit Row。
- 安全异常和拒绝仍可记录 Security Audit。

## 16.2 Support Sensitive Read

Support 查看包含敏感字段的 Ticket Detail 时，写入 Append-only Audit Record：

```text
auditType = SENSITIVE_READ
action = TICKET_VIEWED
actorType
actorId
clientId
resourceType = TICKET
resourceId = ticketId
viewType = SUPPORT_VIEW
fieldsPolicyVersion
traceId
outcome
occurredAt
```

Audit 不保存：

- Title
- Description
- Response Body
- JWT
- Raw scopes

## 16.3 Fail-closed

如果当前 Policy 将该读取定义为 Required Audit，则 Audit Insert 失败：

```text
Read fails closed
```

返回安全的：

```text
HTTP 500
INTERNAL_ERROR
```

不能在未审计的情况下返回敏感详情。

---

# 17. Data Classification and Response Security

| 字段 | 分类 |
|---|---|
| TicketId / DisplayId | INTERNAL |
| Title / Description | SENSITIVE |
| RequesterRef | SENSITIVE |
| ApplicationCode / Source | INTERNAL |
| Status / Priority | INTERNAL |
| SLA Summary | INTERNAL |
| Internal Assignment | INTERNAL / SENSITIVE |
| Workflow Metadata | INTERNAL |
| Secret / Credential | SECRET，禁止 |

Headers：

```http
Cache-Control: private, no-store
Pragma: no-cache
X-Content-Type-Options: nosniff
```

Response 不得被共享 CDN 或公共缓存保存。

---

# 18. Not-found and Error Contract

Error Schema：

```text
schemas/error-envelope.schema.json
```

统一 Envelope：

```json
{
  "error": {
    "code": "TICKET_NOT_FOUND",
    "message": "The Ticket was not found.",
    "traceId": "8f03aabbccddeeff0011223344556677",
    "correlationId": "corr-get-ticket-001",
    "details": {}
  }
}
```

## 18.1 Error Matrix

| 场景 | HTTP | Error Code |
|---|---:|---|
| Invalid Ticket UUID | 400 | `VALIDATION_ERROR` |
| Missing / invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing coarse read Scope | 403 | `FORBIDDEN` |
| Ticket absent | 404 | `TICKET_NOT_FOUND` |
| Resource outside actor scope | 404 | `TICKET_NOT_FOUND` |
| Required read Audit fails | 500 | `INTERNAL_ERROR` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Error Response 不包含：

- Stack Trace
- SQL
- Table Name
- Constraint Name
- Actor permissions
- Whether a hidden Ticket exists
- JWT
- Connection String

---

# 19. Observability

## 19.1 Trace

建议 Spans：

```text
HTTP GET /api/v1/tickets/{ticketId}
GetTicketUseCase
ticket.authorization.resource
db.ticket.employee_view
db.ticket.support_view
db.audit.sensitive_read
```

允许的有限属性：

```text
operation = get_ticket
actor_type
view_type
result
status
application_code when known and bounded
audit_required
conditional_request
```

禁止：

```text
title
description
requesterId
JWT
raw scopes
ticketId as metric label
```

TicketId 可在受控 Trace Attribute 中出现，但不得作为 Metric Label。

## 19.2 Metrics

至少：

```text
opsmind_ticket_get_total
opsmind_ticket_get_duration_seconds
opsmind_ticket_get_not_found_total
opsmind_ticket_get_authorization_denied_total
opsmind_ticket_get_not_modified_total
opsmind_ticket_sensitive_read_audit_failure_total
```

允许 Labels：

```text
actor_type
view_type
result
status_class
conditional_request
```

禁止 Labels：

```text
ticketId
requesterId
traceId
correlationId
clientId
```

## 19.3 Logging

允许：

```text
traceId
correlationId
operation
actorType
viewType
result
duration
safe error code
```

禁止记录：

- Response Body
- Title
- Description
- JWT
- Raw requester identity
- TicketId at INFO level unless policy permits

---

# 20. Performance and Query Requirements

目标：

```text
Get Ticket p95 < 300 ms
Get Ticket p99 < 1 s
```

本 Spec 要求：

- 一次主 Projection Query。
- Required Audit 最多一次 Insert。
- 不发生 N+1。
- 不加载 Message Collection。
- 不加载 Timeline Collection。
- 不加载完整 Audit Collection。
- Query Plan 使用 Ticket PK 和授权相关索引。
- PostgreSQL Integration Test 验证 Query 正确性。
- Query Plan Test 检查明显全表扫描风险。

---

# 21. Response Schema Files

本 Spec 包含：

```text
schemas/
├── employee-ticket-response.schema.json
├── support-ticket-response.schema.json
└── error-envelope.schema.json
```

Schema 要求：

- JSON Schema Draft 2020-12。
- `additionalProperties = false`。
- Date 使用 `date-time`。
- UUID 使用 `uuid`。
- Enum 与 API Contract 一致。
- Employee Schema 不得包含 Internal 字段。
- Support Schema 不得包含 Secret 字段。

---

# 22. Acceptance Scenarios

完整场景位于：

```text
acceptance.feature
```

至少覆盖：

1. Employee 成功查看自己的 Ticket。
2. Employee 查看他人 Ticket 返回 404。
3. Support 成功查看授权 Queue 中的 Ticket。
4. Support 查看授权范围外 Ticket 返回 404。
5. 缺少 Read Scope 返回 403。
6. Ticket 不存在返回 404。
7. Ticket ID 非法返回 400。
8. Employee Response 不包含 Internal 字段。
9. Support Sensitive Read 创建 Audit。
10. Required Audit 失败时读取 Fail Closed。
11. ETag 正确。
12. 匹配 `If-None-Match` 返回 304。
13. `304` 前仍执行授权。
14. Query 不修改 Version 或 `updatedAt`。
15. Query 不产生业务事件或 Outbox Record。

---

# 23. Tests First

## 23.1 Application RED

```text
GetTicketApplicationServiceTest
GetTicketViewPolicyTest
GetTicketConditionalRequestTest
```

## 23.2 API RED

```text
GetTicketControllerTest
GetTicketInvalidIdTest
GetTicketErrorContractTest
GetTicketNotModifiedTest
```

## 23.3 Security RED

```text
GetTicketRequesterOwnershipTest
GetTicketMissingScopeTest
GetTicketSupportAuthorizationTest
GetTicketResourceHidingTest
GetTicketFieldVisibilityTest
GetTicketAuditorPolicyTest
```

## 23.4 PostgreSQL Integration RED

```text
GetTicketEmployeeProjectionIT
GetTicketSupportProjectionIT
GetTicketResourceScopeQueryIT
GetTicketQueryPlanIT
```

## 23.5 Audit and Privacy RED

```text
GetTicketSensitiveReadAuditIT
GetTicketAuditFailureIT
GetTicketResponseRedactionTest
GetTicketTelemetryRedactionTest
```

## 23.6 Non-mutation RED

```text
GetTicketDoesNotMutateTicketIT
GetTicketDoesNotCreateOutboxIT
```

---

# 24. Package and Class Mapping

建议实现：

```text
ticket.api.publicapi
├── PublicTicketQueryController
├── EmployeeTicketDetailResponse
└── PublicTicketQueryApiMapper

ticket.api.support
├── SupportTicketQueryController
├── SupportTicketDetailResponse
└── SupportTicketQueryApiMapper

ticket.application.port.in
└── GetTicketUseCase

ticket.application.query
├── GetTicketQuery
├── GetTicketResult
├── TicketViewType
└── ConditionalGetResult

ticket.application.service
└── GetTicketApplicationService

ticket.application.policy
├── TicketViewPolicy
└── TicketResourceAccessPolicy

ticket.application.port.out
├── TicketQueryPort
└── SensitiveReadAuditPort

ticket.infrastructure.query
├── JdbcTicketQueryAdapter
├── EmployeeTicketProjection
├── SupportTicketProjection
└── TicketQuerySql

ticket.infrastructure.audit
└── SensitiveReadAuditAdapter
```

应用依赖方向：

```text
API
→ Application
→ Port
← Infrastructure
```

禁止：

```text
Controller → JdbcTemplate
Controller → JPA Repository
Application → Repository Implementation
Query DTO → JPA Entity
```

---

# 25. Traceability

计划条目位于：

```text
traceability-entry.yaml
```

实现完成后合并到：

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

最终 Traceability 必须使用真实：

- UC ID
- API ID
- Business Invariant ID
- Security Rule ID
- Class Name
- Test Name

---

# 26. Definition of Done

`SPEC-TW-002` 完成必须满足：

- [ ] Spec 已 Review。
- [ ] Phase 01 已完成。
- [ ] `GET /api/v1/tickets/{ticketId}` 返回正确 Actor View。
- [ ] Employee 只能查看自己的 Ticket。
- [ ] Support 只能查看授权资源。
- [ ] 资源级未授权访问返回安全 404。
- [ ] Employee Schema 不包含 Internal 字段。
- [ ] Support Schema 不包含 Secret。
- [ ] `ETag` 与 Version 一致。
- [ ] 匹配 `If-None-Match` 返回 304。
- [ ] `304` 仍经过 Authentication 和 Authorization。
- [ ] Query 不修改 Ticket。
- [ ] Query 不创建 Outbox Event。
- [ ] Required Sensitive Read Audit 正确。
- [ ] Required Audit 失败时 Fail Closed。
- [ ] PostgreSQL Projection Test 通过。
- [ ] Query Plan Test 通过。
- [ ] No N+1。
- [ ] Error Contract Test 通过。
- [ ] Telemetry Redaction Test 通过。
- [ ] ArchUnit 通过。
- [ ] `./mvnw clean verify` 通过。
- [ ] CI 通过。
- [ ] Traceability 更新。
- [ ] README Curl 示例可执行。

---

# 27. 实现后业务保证

当本 Spec 完成时，OpsMind 可以保证：

```text
每个 Actor 只能看见其被授权查看的 Ticket 和字段；
未授权访问不会泄漏 Ticket 是否存在；
查询快速、稳定、可审计，并且不会修改任何 Ticket 业务状态。
```
