# SPEC-TW-006 — Ticket Timeline

> **Spec ID：** SPEC-TW-006  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **Actors：** EMPLOYEE、IT_SUPPORT、IT_ADMIN、IT_MANAGER、AUDITOR  
> **API：** `GET /api/v1/tickets/{ticketId}/timeline`  
> **依赖：** SPEC-TW-001、SPEC-TW-002、SPEC-TW-004、SPEC-TW-005  
> **业务事件：** 无；纯 Query 不创建 Outbox Event

---

# 1. 目的

本规格定义授权 Actor 查看单张 Ticket 的统一时间线时必须满足的完整行为。

```text
Authenticated Actor
→ Coarse Scope Check
→ Ticket Resource Authorization
→ Server-selected Timeline View
→ Fixed Snapshot Boundary
→ Unified PostgreSQL Projection
→ Visibility Filtering
→ Stable Keyset Pagination
→ Optional Sensitive-read Audit
→ HTTP 200
```

Phase 02 时间线来源：

```text
Ticket Created
Ticket Status History
Public Requester Messages
Public Support Messages
Internal Support Notes
```

系统必须保证：

- Employee 只能查看自己 Ticket 的 Public Timeline。
- Support 只能查看其资源权限范围内的 Ticket。
- Internal Note 只对具有批准 Scope 的 Support / Auditor 可见。
- View 由服务端 Principal 和 Policy 决定。
- 客户端不能通过 Query Parameter 或 Header 选择更高权限 View。
- Timeline 使用固定 `snapshotAt`，同一 Cursor 会话中的项目集合保持稳定。
- 排序确定，并有唯一 Tie-breaker。
- Query 不修改 Ticket、Message、History 或任何业务状态。
- Query 不创建 Status History、Domain Event 或 Outbox Event。
- Employee Response 不泄漏内部 Actor、ReasonCode、Internal Note 或 Audit Metadata。
- Phase 02 不直接把原始 Audit Record 暴露为 Timeline Item。

---

# 2. Scope

包含：

- Employee Public Timeline
- Support Timeline
- Internal Note Visibility
- Auditor Policy Hook
- JWT Authentication
- Resource Authorization
- Actor-specific Projection
- Snapshot Boundary
- Keyset Cursor Pagination
- Stable Timeline Ordering
- Ticket Created Item
- Status Changed Item
- Public Requester Message Item
- Public Support Message Item
- Internal Support Note Item
- Required Sensitive-read Audit
- Error Contract
- Observability
- PostgreSQL Projection / Query Plan Test
- JSON Schema
- Automated Tests

不包含：

- 新增或修改 Timeline Item
- 原始 Audit Log API
- Approval Timeline Detail
- Tool Execution Detail
- Verification Evidence
- Reconciliation Detail
- Attachment Download
- Timeline Search
- Timeline Export
- WebSocket Live Update
- Full-text Search
- Semantic Search
- Arbitrary Sort
- Offset Pagination
- Total Count
- Cross-service Timeline 聚合

后续 Phase 可以扩展新的 Timeline Item Type，但必须更新：

```text
Schema
Item Type Rank
Visibility Policy
Cursor Sort Version
Contract Tests
Traceability
```

---

# 3. HTTP Contract

```http
GET /api/v1/tickets/{ticketId}/timeline
Authorization: Bearer <JWT>
Accept: application/json
```

Supported Query Parameters：

```text
limit
cursor
```

Optional Headers：

```http
traceparent: <W3C trace context>
X-Correlation-Id: <1-128 characters>
```

Response Headers：

```http
Cache-Control: private, no-store
Pragma: no-cache
Vary: Authorization
Content-Type: application/json
```

本 Spec 不使用 Ticket Version 作为 Timeline ETag，因为：

- Message 创建不修改 Ticket Version。
- Timeline 可以变化而 Ticket Version 不变化。
- 错误的 ETag 会导致客户端漏读新 Timeline Item。

MVP 不提供 Conditional GET。

---

# 4. Path Parameter

```text
ticketId
```

规则：

- 必须是规范 UUID。
- 服务端当前生成 UUIDv7。
- 非法 UUID 返回：

```text
400 VALIDATION_ERROR
```

本 API 不接受 Display ID。

---

# 5. Authentication and Coarse Scopes

JWT 必须验证：

- Signature
- Issuer
- Audience
- Expiration
- Not Before
- Subject
- Authorized Party
- Token Type
- Environment

无效 JWT：

```text
401 UNAUTHENTICATED
```

## Employee

需要：

```text
tickets:read:self
```

## Support

需要：

```text
tickets:read:queue
```

查看 Internal Note 还需要：

```text
tickets:timeline:internal
```

或 Security LLD 中批准的等效 Scope。

## Auditor

需要：

```text
tickets:audit:timeline
```

Auditor View 不自动等同 Support View。

缺少总体 Scope：

```text
403 FORBIDDEN
```

---

# 6. Resource Authorization

## Employee

SQL 或授权 Query 必须保证：

```sql
ticket_id = :ticketId
AND requester_id = :principalSubject
```

Employee 访问他人 Ticket：

```text
404 TICKET_NOT_FOUND
```

## Support

必须满足：

```text
Ticket Application
Ticket Team / Queue
Tenant
Region
Data Classification
Assignment Scope
```

Resource Scope 来自可信 Security Context 或本地 Authorization Projection。

同步 Query 不调用远程 Policy Service。

## Auditor

只能访问批准的资源范围和字段。

资源不存在或不在 Actor Scope：

```text
404 TICKET_NOT_FOUND
```

不返回“存在但无权限”。

---

# 7. View Resolution

客户端不能提交：

```text
view=SUPPORT
includeInternal=true
X-Timeline-View: INTERNAL
roles
scopes
```

服务端解析：

```text
EMPLOYEE
→ EMPLOYEE_PUBLIC_VIEW

IT_SUPPORT / IT_ADMIN / IT_MANAGER
+ resource access
+ tickets:timeline:internal
→ SUPPORT_INTERNAL_VIEW

IT_SUPPORT / IT_ADMIN / IT_MANAGER
+ resource access
without internal scope
→ SUPPORT_PUBLIC_VIEW

AUDITOR
→ AUDITOR_POLICY_VIEW
```

多角色 Principal 使用明确 Policy，不自动选择字段最多的 View。

---

# 8. Timeline Sources

## 8.1 Ticket Created

来源：

```text
ticket.tickets
```

映射：

```text
itemType = TICKET_CREATED
visibility = PUBLIC
occurredAt = ticket.created_at
actorType = created_by_type
relatedVersion = 0
```

不返回完整 Request Body。

## 8.2 Status History

来源：

```text
ticket.ticket_status_history
```

映射：

```text
itemType = STATUS_CHANGED
visibility = PUBLIC
occurredAt = history.occurred_at
relatedVersion = aggregate_version
```

Employee View：

- 可以看到 `fromStatus` 和 `toStatus`。
- 只返回批准的公开 Summary。
- 不返回 Internal ReasonCode、SourceEventId、WorkflowId 或 ActorId。

Support View 可以根据 Policy 返回：

- `transitionId`
- `reasonCode`
- Pseudonymous ActorRef

但仍不返回 Secret 或原始 Event Payload。

## 8.3 Public Requester Message

来源：

```text
ticket.ticket_messages
```

条件：

```text
message_type = PUBLIC_REQUESTER_MESSAGE
visibility = PUBLIC
```

Employee 和 Support 均可见。

## 8.4 Public Support Message

条件：

```text
message_type = PUBLIC_SUPPORT_MESSAGE
visibility = PUBLIC
```

Employee 和 Support 均可见。

## 8.5 Internal Support Note

条件：

```text
message_type = INTERNAL_SUPPORT_NOTE
visibility = INTERNAL
```

只有批准的 Support / Auditor View 可见。

---

# 9. Phase 02 不使用原始 Audit Record 作为 Timeline Source

Phase 02 不直接读取：

```text
ticket.audit_records
```

并把它原样返回给用户。

原因：

- Audit Record 具有不同的合规目的。
- Audit Metadata 可能包含内部资源标识和安全决策信息。
- 原始 Audit 不是用户沟通时间线。

未来需要展示安全的业务动作时，应创建：

```text
Audit-safe Timeline Projection
```

并通过新 Spec / Contract 明确字段与 Visibility。

---

# 10. Timeline Item Identity

Phase 02 不额外创建 Timeline Table。

每个 Timeline Item 使用稳定、跨来源唯一的 `itemId`：

```text
TICKET_CREATED:<ticketId>
STATUS_HISTORY:<historyId>
MESSAGE:<messageId>
```

要求：

- 创建后不变。
- 不包含 Secret。
- 在 Timeline 内唯一。
- 可作为 Cursor Tie-breaker。
- 不作为 Metric Label。

如果未来引入专用 Timeline Projection，应保持旧 Item ID 的兼容映射。

---

# 11. Timeline Item Types and Rank

Sort Version 1：

```text
0 = TICKET_CREATED
1 = STATUS_CHANGED
2 = PUBLIC_REQUESTER_MESSAGE
3 = PUBLIC_SUPPORT_MESSAGE
4 = INTERNAL_SUPPORT_NOTE
```

`itemTypeRank` 不需要返回给客户端，但 Cursor 和 SQL 必须使用同一映射。

新增 Item Type 时：

- 不得静默插入现有 Rank 中。
- 必须升级 `sortVersion`。
- 旧 Cursor 返回 `INVALID_CURSOR` 或在兼容层中继续按旧规则处理。

---

# 12. Ordering

默认：

```text
occurredAt ASC
itemTypeRank ASC
itemId ASC
```

含义：

- 从 Ticket 创建开始按时间向后展示。
- 相同时间先显示 Ticket Created，再显示 Status，再显示 Message。
- `itemId` 是唯一 Tie-breaker。

MVP 不允许客户端自定义 Sort Direction。

---

# 13. Snapshot Semantics

第一页请求时，服务端生成：

```text
snapshotAt = trusted service Clock
```

所有页仅返回：

```text
occurredAt <= snapshotAt
```

Cursor 保存相同 `snapshotAt`。

因此：

- 第一页之后新增的 Message 或 Status History 不会混入旧 Cursor 的后续页。
- 用户刷新 Timeline 后获得新的 Snapshot，并看到新项目。
- 同一 Cursor 会话是稳定的 Snapshot Read，而不是 Live Queue。

Response：

```text
consistency = SNAPSHOT
```

Phase 02 Timeline Source 必须使用服务端控制的发生时间。

纠正或补偿记录应创建新的当前时间 Timeline Item，不应通过回写旧时间破坏 Snapshot 语义。

---

# 14. Page Size

```text
default = 50
minimum = 1
maximum = 100
```

非法值：

```text
400 VALIDATION_ERROR
```

不允许无界 Timeline。

---

# 15. Cursor Pagination

Cursor Payload 至少包含：

```json
{
  "version": 1,
  "operation": "ticketTimeline",
  "ticketFingerprint": "hmac-sha256:...",
  "actorFingerprint": "hmac-sha256:...",
  "scopeFingerprint": "hmac-sha256:...",
  "viewType": "EMPLOYEE_PUBLIC_VIEW",
  "visibilityPolicyVersion": 1,
  "snapshotAt": "2026-07-25T20:00:00Z",
  "lastOccurredAt": "2026-07-25T18:30:00Z",
  "lastItemTypeRank": 2,
  "lastItemId": "MESSAGE:0190abcd-1234-7000-8000-000000000001",
  "sortVersion": 1,
  "issuedAt": "2026-07-25T20:00:00Z",
  "expiresAt": "2026-07-26T20:00:00Z"
}
```

格式建议：

```text
base64url(payload) + "." + base64url(HMAC-SHA-256(payload))
```

TTL：

```text
24 hours
```

Cursor 必须绑定：

- Operation
- Ticket
- Actor
- Scope
- View Type
- Visibility Policy Version
- Snapshot
- Sort Version

以下返回：

```text
400 INVALID_CURSOR
```

- Malformed
- Invalid Signature
- Expired
- Ticket Mismatch
- Actor Mismatch
- Scope Changed
- View Changed
- Policy Version Changed
- Sort Version Changed
- Operation Mismatch

当 Actor 权限变化时，旧 Cursor 失效。

---

# 16. Keyset Predicate

逻辑形式：

```sql
AND occurred_at <= :snapshotAt
AND (
  occurred_at > :lastOccurredAt
  OR (
    occurred_at = :lastOccurredAt
    AND item_type_rank > :lastItemTypeRank
  )
  OR (
    occurred_at = :lastOccurredAt
    AND item_type_rank = :lastItemTypeRank
    AND item_id > :lastItemId
  )
)
```

配合：

```sql
ORDER BY
  occurred_at ASC,
  item_type_rank ASC,
  item_id ASC
LIMIT :limitPlusOne
```

读取 `limit + 1` 条判断 `hasMore`。

---

# 17. Query Architecture

```text
TicketTimelineController
→ GetTicketTimelineUseCase
→ GetTicketTimelineApplicationService
→ TicketTimelineQueryPort
→ JdbcTicketTimelineQueryAdapter
→ PostgreSQL UNION ALL Projection
```

原则：

- Query Side 使用明确 SQL / JDBC Projection。
- 不重建 Ticket Aggregate。
- 不使用 JPA Lazy Graph。
- Authorization 和 Visibility 尽量进入 SQL。
- Employee Query 不读取 Internal Note Row。
- 不先读取 Internal Note 再在 Java 中删除。
- 不调用远程服务。
- 默认 `READ COMMITTED`。
- Snapshot 边界由 SQL 强制。
- Query 不执行业务写入。

---

# 18. SQL Projection Shape

逻辑形态：

```sql
WITH timeline_items AS (
  SELECT
    'TICKET_CREATED:' || t.ticket_id AS item_id,
    'TICKET_CREATED' AS item_type,
    0 AS item_type_rank,
    'PUBLIC' AS visibility,
    t.created_at AS occurred_at,
    ...
  FROM ticket.tickets t
  WHERE t.ticket_id = :ticketId
    AND <resource authorization>

  UNION ALL

  SELECT
    'STATUS_HISTORY:' || h.history_id,
    'STATUS_CHANGED',
    1,
    'PUBLIC',
    h.occurred_at,
    ...
  FROM ticket.ticket_status_history h
  WHERE h.ticket_id = :ticketId

  UNION ALL

  SELECT
    'MESSAGE:' || m.message_id,
    m.message_type,
    CASE m.message_type
      WHEN 'PUBLIC_REQUESTER_MESSAGE' THEN 2
      WHEN 'PUBLIC_SUPPORT_MESSAGE' THEN 3
      WHEN 'INTERNAL_SUPPORT_NOTE' THEN 4
    END,
    m.visibility,
    m.created_at,
    ...
  FROM ticket.ticket_messages m
  WHERE m.ticket_id = :ticketId
    AND (
      :includeInternal = TRUE
      OR m.visibility = 'PUBLIC'
    )
)
SELECT ...
FROM timeline_items
WHERE occurred_at <= :snapshotAt
  AND <keyset predicate>
ORDER BY occurred_at, item_type_rank, item_id
LIMIT :limitPlusOne
```

Employee SQL 应使用：

```text
m.visibility = PUBLIC
```

而不是加载全部 Message 后再过滤。

---

# 19. Employee Timeline Response

Schema：

```text
schemas/employee-timeline-response.schema.json
```

Employee Item 允许：

- Stable Item ID
- Item Type
- `visibility = PUBLIC`
- OccurredAt
- Safe Actor Label
- Safe Summary
- Public Message Content
- Public Status Metadata
- Related Version

Employee Item 禁止：

- Actor ID
- AuthorRef
- Internal Note
- Internal ReasonCode
- Transition Source Event
- Workflow ID
- Audit Metadata
- Tool / Approval / Verification Internals
- Secret

Employee Message Actor Label 示例：

```text
You
IT Support
System
```

服务端不返回 Support 个人内部标识。

---

# 20. Support Timeline Response

Schema：

```text
schemas/support-timeline-response.schema.json
```

Support Public View：

- Public Item only。

Support Internal View：

- Public Item。
- Internal Support Note。
- 批准的 Internal ReasonCode。
- Pseudonymous ActorRef。

Support Response 仍禁止：

- JWT
- Credential
- Tool Secret
- Full Audit Record
- 未批准的 Requester Identity Attribute
- 原始 Event Payload

---

# 21. Auditor Policy View

Auditor：

- 必须拥有批准的 Timeline Audit Scope。
- 字段由 `AUDITOR_POLICY_VIEW` 决定。
- 默认不返回完整 Message Content，除非 Policy 明确批准。
- Timeline Read 本身必须写入 Security Audit。
- 本 Spec 提供 Policy Hook；专用 Auditor API 可在后续独立定义。

---

# 22. Empty Timeline

正常 Ticket 至少包含：

```text
TICKET_CREATED
STATUS_CHANGED Initial → NEW
```

因此生产环境下通常不会为空。

但对于迁移、测试或修复场景，如果授权 Ticket 没有 Timeline Source：

```text
HTTP 200
items = []
hasMore = false
nextCursor = null
```

不能返回 `404`，因为 Ticket 本身存在。

---

# 23. Sensitive-read Audit

## Employee

Employee 查看自己的 Public Timeline：

- 记录标准 Trace、Metric 和安全访问日志。
- 默认不创建高成本 Business Audit Row。

## Support

当 Response 包含 Internal Item 或敏感内部字段时，写入：

```text
auditType = SENSITIVE_READ
action = TICKET_TIMELINE_VIEWED
actorType
actorId
clientId
resourceType = TICKET
resourceId = ticketId
viewType
includedInternal
resultCount
visibilityPolicyVersion
traceId
outcome
occurredAt
```

Audit 不保存：

- Item ID List
- Message Content
- Summary
- Cursor
- Response Body
- JWT

Required Audit 失败：

```text
Fail Closed
HTTP 500 INTERNAL_ERROR
```

敏感 Timeline Body 不得返回。

## Auditor

Auditor Timeline Read 始终记录 Security Audit。

---

# 24. Error Contract

| 场景 | HTTP | Code |
|---|---:|---|
| Invalid Ticket ID / limit | 400 | `VALIDATION_ERROR` |
| Invalid / expired cursor | 400 | `INVALID_CURSOR` |
| Missing / invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing coarse Scope | 403 | `FORBIDDEN` |
| Ticket absent / hidden | 404 | `TICKET_NOT_FOUND` |
| Required Audit fails | 500 | `INTERNAL_ERROR` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Error 不暴露：

- Ticket 是否存在但无权限
- Internal Item 是否存在
- Actor Scope
- Cursor Payload
- SQL
- Table / Index
- Message Content
- JWT
- Secret

---

# 25. Observability

Trace：

```text
HTTP GET /api/v1/tickets/{ticketId}/timeline
GetTicketTimelineUseCase
ticket.timeline.view.resolve
ticket.timeline.authorization
ticket.timeline.cursor.decode
ticket.timeline.cursor.validate
db.ticket.timeline.query
db.audit.timeline_read
ticket.timeline.cursor.encode
```

Metrics：

```text
opsmind_ticket_timeline_query_total
opsmind_ticket_timeline_query_duration_seconds
opsmind_ticket_timeline_result_count
opsmind_ticket_timeline_invalid_cursor_total
opsmind_ticket_timeline_authorization_denied_total
opsmind_ticket_timeline_internal_view_total
opsmind_ticket_timeline_audit_failure_total
```

允许低基数 Labels：

```text
actor_type
view_type
result
status_class
cursor_present
has_more
included_internal
```

禁止：

```text
ticketId
itemId
actorId
requesterId
raw cursor
messageType as unbounded custom value
```

Log 不记录：

- Timeline Response
- Message Content
- Internal Reason
- Raw Cursor
- JWT
- Secret

---

# 26. Performance

目标：

```text
Timeline p95 < 400 ms
Timeline p99 < 1.2 s
```

要求：

- Page Size 最大 100。
- 一次主 UNION ALL Query。
- Required Audit 最多一次 Insert。
- 无 Count Query。
- 无 Offset。
- 无 N+1。
- Employee Query 不读取 Internal Row。
- Response 有界。
- Query Plan 使用 Source Index。

---

# 27. Index Strategy

必须评估：

```text
ticket.tickets(ticket_id)
ticket.ticket_status_history(ticket_id, occurred_at ASC, history_id ASC)
ticket.ticket_messages(ticket_id, created_at ASC, message_id ASC)
```

Internal View 可评估：

```text
(ticket_id, visibility, created_at ASC, message_id ASC)
```

只有 Query Plan 证明需要时创建额外索引。

验证：

```text
EXPLAIN (ANALYZE, BUFFERS)
```

---

# 28. Tests First

## Application

```text
GetTicketTimelineApplicationServiceTest
TicketTimelineViewPolicyTest
TicketTimelineMappingTest
TicketTimelinePageSizeTest
```

## Authorization / Visibility

```text
TicketTimelineRequesterOwnershipIT
TicketTimelineSupportScopeIT
TicketTimelineResourceHidingTest
EmployeeTimelineVisibilityTest
SupportPublicTimelineVisibilityTest
SupportInternalTimelineVisibilityTest
AuditorTimelinePolicyTest
```

## Cursor / Snapshot

```text
TicketTimelineCursorCodecTest
TicketTimelineCursorSignatureTest
TicketTimelineCursorExpiryTest
TicketTimelineCursorTicketBindingTest
TicketTimelineCursorActorBindingTest
TicketTimelineCursorScopeBindingTest
TicketTimelineCursorViewBindingTest
TicketTimelineCursorSortVersionTest
TicketTimelineSnapshotPaginationIT
TicketTimelineNewItemAfterSnapshotIT
```

## API

```text
TicketTimelineControllerTest
TicketTimelineInvalidIdTest
TicketTimelineEmptyResponseTest
TicketTimelineErrorContractTest
```

## PostgreSQL

```text
TicketTimelineProjectionIT
TicketTimelineOrderingIT
TicketTimelineEqualTimestampTieBreakerIT
TicketTimelinePublicOnlyQueryIT
TicketTimelineInternalQueryIT
TicketTimelineQueryPlanIT
```

## Audit / Privacy / Non-mutation

```text
TicketTimelineSensitiveReadAuditIT
TicketTimelineAuditFailureIT
TicketTimelineResponseRedactionTest
TicketTimelineTelemetryRedactionTest
TicketTimelineDoesNotMutateTicketIT
TicketTimelineDoesNotCreateOutboxIT
```

---

# 29. Package Mapping

```text
ticket.api.publicapi
├── TicketTimelineController
├── EmployeeTimelineResponse
├── EmployeeTimelineItemResponse
└── EmployeeTimelineApiMapper

ticket.api.support
├── SupportTicketTimelineController
├── SupportTimelineResponse
├── SupportTimelineItemResponse
└── SupportTimelineApiMapper

ticket.application.port.in
└── GetTicketTimelineUseCase

ticket.application.query
├── TicketTimelineQuery
├── TicketTimelineResult
├── TicketTimelineItem
├── TicketTimelineCursor
├── TicketTimelineViewType
├── TicketTimelineSortVersion
└── TimelineSnapshot

ticket.application.policy
└── TicketTimelineViewPolicy

ticket.application.service
└── GetTicketTimelineApplicationService

ticket.application.port.out
├── TicketTimelineQueryPort
└── SensitiveReadAuditPort

ticket.infrastructure.query
├── JdbcTicketTimelineQueryAdapter
├── TicketTimelineProjection
├── TicketTimelineCursorCodec
├── TicketTimelineCursorSigner
└── TicketTimelineQuerySql
```

---

# 30. Traceability

计划条目位于：

```text
traceability-entry.yaml
```

实现完成后合并到：

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

必须核对并更新真实：

- Use Case ID
- API ID
- Business Invariant ID
- Security Rule ID
- Class
- Test
- Index / Migration
- Visibility Policy Version

---

# 31. Definition of Done

- [ ] Employee 只能读取自己 Ticket 的 Public Timeline。
- [ ] Support 只能读取授权 Ticket。
- [ ] Internal Note 需要独立 Scope。
- [ ] View 由服务端 Policy 决定。
- [ ] Resource Authorization 和 Visibility 下推到 SQL。
- [ ] Employee Query 不读取 Internal Note Row。
- [ ] Timeline Source Mapping 正确。
- [ ] 默认排序为 `occurredAt ASC, itemTypeRank ASC, itemId ASC`。
- [ ] Cursor 使用 Keyset Pagination。
- [ ] Cursor 绑定 Ticket、Actor、Scope、View、Snapshot 和 Sort。
- [ ] 同一 Cursor 会话使用固定 `snapshotAt`。
- [ ] 新 Item 不进入旧 Snapshot。
- [ ] Empty Timeline 返回 200。
- [ ] Employee Response 无 Actor ID、Internal Reason 或 Internal Note。
- [ ] Support Sensitive Read Audit 正确。
- [ ] Required Audit 失败时 Fail Closed。
- [ ] Query 不修改 Ticket、History 或 Message。
- [ ] Query 不创建 Outbox。
- [ ] 无 Offset、Count Query 和 N+1。
- [ ] Query Plan 达到基线。
- [ ] Schema、Cursor、Visibility、Audit 和 Redaction Test 通过。
- [ ] PostgreSQL、ArchUnit、`./mvnw clean verify` 和 CI 通过。
- [ ] Traceability 更新。

---

# 32. 实现后保证

```text
Employee 和 Support 可以在严格权限边界内查看统一 Ticket 时间线；
Employee 永远看不到内部备注和内部安全信息；
同一分页会话具有稳定 Snapshot 和确定顺序；
敏感 Timeline Read 可审计；
查询不会改变任何 Ticket 生命周期数据。
```
