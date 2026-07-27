# SPEC-TW-005 — Support Queue Query

> **Spec ID：** SPEC-TW-005  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **Actors：** IT_SUPPORT、IT_ADMIN、IT_MANAGER  
> **API：** `GET /api/v1/support/tickets`  
> **依赖：** SPEC-TW-001、SPEC-TW-002、SPEC-TW-003  
> **业务事件：** 无；纯 Query 不创建 Outbox Event

---

# 1. 目的

定义 IT Support 查询其被授权处理的 Ticket Queue 时必须满足的完整行为。

```text
Authenticated Support Actor
→ tickets:read:queue
→ Trusted Queue Scope
→ SQL-level Resource Authorization
→ Whitelisted Filters
→ SLA/Priority Ranking
→ Signed Keyset Cursor
→ Minimal Support Summaries
```

系统必须保证：

- 只有 Support 角色可以调用本 API。
- Support 只能读取其 Application、Team、Tenant、Region 和 Sensitivity Scope 内的 Ticket。
- Resource Authorization 进入 SQL，而不是查询后在 Java 中过滤。
- 默认 Queue 只包含非终态 Ticket。
- Queue 排序确定、有唯一 Tie-breaker。
- SLA 时间计算在同一分页会话中使用固定 `evaluationTime`。
- Cursor 防篡改，并绑定 Filter、Sort、Scope、Operation 和 Actor。
- Response 最小化敏感数据。
- Query 有界、可分页、无 Offset、无 Total Count、无 N+1。
- Query 不修改 Ticket，也不创建 History、Audit Business Event 或 Outbox Event。

---

# 2. Scope

包含：

- Support Authentication
- `tickets:read:queue`
- Support Queue Resource Scope
- Default Non-terminal Queue
- Status / Priority / Application / Assignment / SLA Filters
- Stable Deterministic Sort
- Keyset Cursor Pagination
- Cursor Scope Binding
- Minimal Support Ticket Summary
- Empty Queue
- Filter-scope Validation
- Query Audit Policy Hook
- Metrics、Trace、Safe Logs
- PostgreSQL Projection 和 Query Plan Test

不包含：

- Assignment Command
- Claim Ticket
- Escalate Ticket
- Triage Command
- Full Ticket Detail
- Message Content
- Timeline
- Full-text Search
- Semantic Search
- Arbitrary Dynamic Query
- Offset Pagination
- Total Count
- CSV / Export
- Historical Snapshot Export
- Dashboard Aggregation

---

# 3. HTTP Contract

```http
GET /api/v1/support/tickets
Authorization: Bearer <JWT>
Accept: application/json
```

Supported Query Parameters：

```text
limit
cursor
status
priority
applicationCode
assignedTeam
assignedAgent
unassignedOnly
slaState
createdFrom
createdTo
```

Optional Headers：

```http
traceparent: <W3C trace context>
X-Correlation-Id: <1-128 characters>
```

Response Headers：

```http
Cache-Control: private, no-store
Vary: Authorization
Content-Type: application/json
```

---

# 4. Authentication and Role

允许角色：

```text
IT_SUPPORT
IT_ADMIN
IT_MANAGER
```

不允许：

```text
EMPLOYEE
AUDITOR without queue-operation scope
SERVICE_ACCOUNT without approved support role
```

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

需要：

```text
tickets:read:queue
```

缺少 Scope：

```text
403 FORBIDDEN
```

---

# 5. Trusted Support Queue Scope

Queue Scope 必须来自：

- 已验证 JWT 中受信任的组织/团队 Claim；或
- Ticket Workflow 本地授权 Projection；或
- 已批准的本地 Policy Adapter。

同步 Query 不调用远程 Policy Service。

推荐模型：

```text
SupportQueueScope
├── allowedApplicationCodes
├── allowedTeamIds
├── allowedTenantIds
├── allowedRegions
├── maximumDataClassification
└── allowUnassigned
```

客户端不得通过 Query Parameter 扩大 Scope。

---

# 6. SQL-level Resource Authorization

SQL 至少包含：

```text
ticket application ∈ allowedApplicationCodes
AND ticket tenant ∈ allowedTenantIds
AND ticket region ∈ allowedRegions
AND ticket classification ≤ maximumDataClassification
AND assignment satisfies allowedTeamIds / allowUnassigned
```

禁止：

```text
SELECT broad queue
→ Java memory filter
```

Authorization Predicate 和业务 Filter 必须同时进入参数化 SQL。

---

# 7. Filter Scope Validation

客户端请求的 Filter 必须是 Actor Scope 的子集。

示例：

```text
Actor allowed applications = [VPN, EMAIL]
Request applicationCode = HOUSING_PORTAL
→ 403 FILTER_OUTSIDE_AUTHORIZED_SCOPE
```

这样可区分：

- 缺少总体 Queue Scope：`403 FORBIDDEN`
- 请求超出已知 Queue Scope：`403 FILTER_OUTSIDE_AUTHORIZED_SCOPE`

不返回任何 Ticket 数据。

对于未显式请求的 Scope Dimension，系统自动使用 Actor 的全部批准范围。

---

# 8. Default Queue

默认条件：

```text
status NOT IN (CLOSED, CANCELLED)
```

是否包含 `RESOLVED`：

```text
默认包含 RESOLVED，直到 Auto-close 或人工关闭
```

默认 Queue 状态：

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
EXECUTING
VERIFYING
RESOLVED
ESCALATED
FAILED
```

`CLOSED` 和 `CANCELLED` 只有在未来明确 History Search Spec 中查询，不属于默认 Support Queue。

---

# 9. Filter Contract

## `status`

允许一个或多个冻结的 TicketStatus。

客户端显式请求 `CLOSED` 或 `CANCELLED` 时，MVP 返回：

```text
400 VALIDATION_ERROR
```

因为本 Endpoint 是 Operational Queue，不是历史查询。

## `priority`

允许：

```text
UNASSIGNED
P1
P2
P3
P4
```

## `applicationCode`

必须是 Actor `allowedApplicationCodes` 的子集。

## `assignedTeam`

必须是 Actor `allowedTeamIds` 的子集。

## `assignedAgent`

- IT_SUPPORT 默认只能筛选自己或其管理范围内的 Agent。
- IT_MANAGER / IT_ADMIN 根据批准 Scope 可以筛选更多 Agent。
- Raw Agent ID 必须验证格式并在授权 Projection 中确认。

## `unassignedOnly`

```text
true / false
```

当 `true` 时：

```text
assignedAgent IS NULL
```

与显式 `assignedAgent` 同时出现时返回 `VALIDATION_ERROR`。

## `slaState`

允许：

```text
BREACHED
AT_RISK
ACTIVE
PAUSED
COMPLETED
```

`AT_RISK` 可以由本地 SLA Policy 和 `evaluationTime` 计算。

## `createdFrom` / `createdTo`

语义：

```text
created_at >= createdFrom
created_at < createdTo
```

要求：

```text
createdFrom < createdTo
```

建议最大区间：

```text
180 days
```

## Filter Canonicalization

Fingerprint 前：

- Enum 排序并去重。
- ID 排序并去重。
- Date-time 转为 UTC。
- Boolean 使用稳定表示。
- `limit` 和 `cursor` 不进入 Filter Fingerprint。

---

# 10. Page Size

```text
default = 25
minimum = 1
maximum = 100
```

超过范围：

```text
400 VALIDATION_ERROR
```

服务端不得返回无界 Queue。

---

# 11. SLA Urgency Ranking

同一分页会话使用 Cursor 中固定的：

```text
evaluationTime
```

排序等级：

```text
0 = BREACHED
1 = AT_RISK
2 = ACTIVE
3 = PAUSED
4 = COMPLETED
```

`AT_RISK` 的阈值来自本地 SLA Policy，例如：

```text
remaining time <= configured risk window
```

不能在 Query 中调用远程 SLA Service。

固定 `evaluationTime` 的目的：

- 第一页和后续页不会因为时间流逝而改变同一 Ticket 的 SLA Rank。
- Cursor 过期后重新查询，使用新的 Evaluation Time。

---

# 12. Priority Ranking

```text
0 = P1
1 = P2
2 = P3
3 = P4
4 = UNASSIGNED
```

Priority 是可变字段。

因此本 API 的语义是：

```text
LIVE operational queue
```

当 Ticket Priority、Status、Assignment 或 SLA State 在分页期间发生业务更新时，该 Ticket 可能移动到其他位置。

本 Spec 保证：

- 相同 Evaluation Time 下的时间型 SLA 计算稳定。
- 未发生 Queue Sort Key 更新的记录不会因 Cursor 算法重复。
- 不承诺跨多个请求的历史快照一致性。
- 需要完整快照导出时应创建独立 Reporting Spec。

---

# 13. Default Sort

```text
slaRank ASC
priorityRank ASC
createdAt ASC
ticketId ASC
```

含义：

1. 已 Breach 优先。
2. 即将 Breach 次之。
3. 更高 Priority 优先。
4. 同等级中更早创建的 Ticket 优先。
5. TicketId 是唯一 Tie-breaker。

SQL 必须使用与 Cursor 一致的 Rank 表达式。

---

# 14. Cursor Pagination

使用 Keyset Pagination。

Cursor 至少包含：

```json
{
  "version": 1,
  "operation": "supportQueue",
  "evaluationTime": "2026-07-25T19:00:00Z",
  "lastSlaRank": 1,
  "lastPriorityRank": 2,
  "lastCreatedAt": "2026-07-23T16:30:00Z",
  "lastTicketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "filterFingerprint": "sha256:...",
  "scopeFingerprint": "hmac-sha256:...",
  "actorFingerprint": "hmac-sha256:...",
  "sortVersion": 1,
  "issuedAt": "2026-07-25T19:00:00Z",
  "expiresAt": "2026-07-25T20:00:00Z"
}
```

格式建议：

```text
base64url(payload) + "." + base64url(HMAC-SHA-256(payload))
```

TTL：

```text
1 hour
```

Queue 变化较快，因此 TTL 短于 Requester List Cursor。

Cursor 必须绑定：

- Operation
- Actor
- Queue Scope
- Filter
- Sort Version
- Evaluation Time

以下返回：

```text
400 INVALID_CURSOR
```

- Malformed
- Signature Invalid
- Expired
- Actor Mismatch
- Scope Changed
- Filter Mismatch
- Sort Version Mismatch
- Operation Mismatch

当 Support 权限在翻页期间发生变化，旧 Cursor 必须失效。

---

# 15. Keyset Predicate

逻辑形式：

```sql
AND (
  sla_rank > :lastSlaRank
  OR (
    sla_rank = :lastSlaRank
    AND priority_rank > :lastPriorityRank
  )
  OR (
    sla_rank = :lastSlaRank
    AND priority_rank = :lastPriorityRank
    AND created_at > :lastCreatedAt
  )
  OR (
    sla_rank = :lastSlaRank
    AND priority_rank = :lastPriorityRank
    AND created_at = :lastCreatedAt
    AND ticket_id > :lastTicketId
  )
)
```

配合：

```sql
ORDER BY
  sla_rank ASC,
  priority_rank ASC,
  created_at ASC,
  ticket_id ASC
LIMIT :limitPlusOne
```

读取 `limit + 1` 条判断 `hasMore`。

---

# 16. Response Contract

Schema：

```text
schemas/support-queue-response.schema.json
```

示例：

```json
{
  "items": [
    {
      "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
      "displayId": "INC-2048",
      "title": "Cannot sign in to Housing Portal",
      "applicationCode": "HOUSING_PORTAL",
      "status": "INVESTIGATING",
      "priority": "P2",
      "requesterRef": "usr_7f2d8a",
      "assignment": {
        "teamId": "TEAM-HOUSING",
        "agentId": null,
        "unassigned": true
      },
      "sla": {
        "state": "AT_RISK",
        "responseDueAt": "2026-07-25T19:15:00Z",
        "resolutionDueAt": "2026-07-26T16:30:00Z",
        "urgencyRank": 1
      },
      "createdAt": "2026-07-23T16:30:00Z",
      "updatedAt": "2026-07-25T18:30:00Z",
      "version": 4
    }
  ],
  "page": {
    "limit": 25,
    "hasMore": true,
    "nextCursor": "opaque-signed-cursor",
    "evaluationTime": "2026-07-25T19:00:00Z",
    "consistency": "LIVE"
  },
  "sort": {
    "version": 1,
    "fields": [
      "slaRank:asc",
      "priorityRank:asc",
      "createdAt:asc",
      "ticketId:asc"
    ]
  },
  "appliedFilters": {
    "status": [],
    "priority": [],
    "applicationCode": [],
    "assignedTeam": [],
    "assignedAgent": null,
    "unassignedOnly": false,
    "slaState": [],
    "createdFrom": null,
    "createdTo": null
  }
}
```

---

# 17. Support Ticket Summary

允许：

- TicketId
- DisplayId
- Title
- ApplicationCode
- Status
- Priority
- Pseudonymous RequesterRef
- Minimal Assignment Summary
- Minimal SLA Summary
- CreatedAt
- UpdatedAt
- Version

禁止：

- Full Description
- Message Content
- Internal Note Content
- Requester Email
- Raw requester identity attributes
- Approval payload
- Tool arguments / credentials
- Verification evidence
- Reconciliation details
- Full Audit metadata
- Secret

Support 需要详情时使用 `SPEC-TW-002 Get Ticket`。

---

# 18. Empty Queue

没有匹配 Ticket 时：

```text
HTTP 200
items = []
hasMore = false
nextCursor = null
```

不返回 `404`。

---

# 19. Query Architecture

```text
SupportTicketQueryController
→ QuerySupportQueueUseCase
→ QuerySupportQueueApplicationService
→ SupportQueueQueryPort
→ JdbcSupportQueueQueryAdapter
→ PostgreSQL Projection
```

规则：

- 使用明确 JDBC SQL / Projection。
- 不重建 Ticket Aggregate。
- 不使用 JPA Lazy Graph。
- Authorization 和 Filter 在 SQL。
- 不读取 Description、Messages、Timeline。
- 不调用远程服务。
- 可以使用 read-only transaction。
- 默认 `READ COMMITTED`。
- 不修改任何业务数据。

MVP 可直接 Join：

```text
ticket.tickets
current Resolution Cycle
current SLA Cycle
current Assignment
```

如果 Query Plan 不满足目标，应通过 ADR 引入专用 `support_queue_projection`，不能静默复制业务数据。

---

# 20. Index Strategy

至少评估：

```text
application_code
assigned_team_id
assigned_agent_id
status
priority
created_at
ticket_id
current_sla_cycle_id
```

推荐根据真实 Query 建立部分索引，例如：

```sql
WHERE status NOT IN ('CLOSED', 'CANCELLED')
```

可能的组合索引：

```text
(application_code, status, priority, created_at, ticket_id)
(assigned_team_id, status, priority, created_at, ticket_id)
(assigned_agent_id, status, priority, created_at, ticket_id)
```

SLA 排序可能需要：

- 当前 SLA Cycle Index；或
- 经过 ADR 批准的 Denormalized Rank Projection。

所有索引必须用：

```text
EXPLAIN (ANALYZE, BUFFERS)
```

和代表性 Test Data 证明。

---

# 21. Audit

普通 Queue List 不为每个返回行创建 Audit Record。

Policy 可以要求每次 Queue Access 写入一条摘要 Audit：

```text
action = SUPPORT_QUEUE_ACCESSED
actorId
scopeFingerprint
filterFingerprint
resultCount
traceId
occurredAt
```

Audit 不保存：

- Ticket ID 列表
- Title
- RequesterRef
- Cursor
- Response Body

打开单张敏感 Ticket Detail 的 Audit 由 SPEC-TW-002 负责。

---

# 22. Observability

Trace：

```text
HTTP GET /api/v1/support/tickets
QuerySupportQueueUseCase
support.queue.scope.resolve
support.queue.filter.validate
support.queue.cursor.decode
support.queue.cursor.validate
db.support.queue.query
support.queue.cursor.encode
```

Metrics：

```text
opsmind_support_queue_query_total
opsmind_support_queue_query_duration_seconds
opsmind_support_queue_result_count
opsmind_support_queue_invalid_cursor_total
opsmind_support_queue_forbidden_filter_total
opsmind_support_queue_authorization_denied_total
```

允许低基数 Labels：

```text
actor_type
result
status_class
cursor_present
has_filters
unassigned_only
```

禁止：

```text
ticketId
teamId
agentId
requesterRef
raw cursor
scopeFingerprint
```

Log 不保存 Raw Cursor、Ticket Summary 或 Scope 明细。

---

# 23. Performance

目标：

```text
Support Queue p95 < 400 ms
Support Queue p99 < 1.2 s
```

要求：

- 默认 Page Size 25，最大 100。
- 一次主 Query。
- 无 Count Query。
- 无 N+1。
- 无 Offset。
- Response 有界。
- Query Plan 使用 Scope 和 Queue Filter Index。
- 不加载 Description 或 Messages。

---

# 24. Error Contract

| 场景 | HTTP | Code |
|---|---:|---|
| Invalid filter / limit / date | 400 | `VALIDATION_ERROR` |
| Invalid / expired cursor | 400 | `INVALID_CURSOR` |
| Missing / invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing queue scope | 403 | `FORBIDDEN` |
| Requested filter outside scope | 403 | `FILTER_OUTSIDE_AUTHORIZED_SCOPE` |
| Rate limited | 429 | `RATE_LIMITED` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Error 不暴露：

- Actor 的完整 Scope
- 可访问的 Team / Application 列表
- Cursor Payload
- SQL
- Table / Index
- JWT
- Ticket 数据

---

# 25. Rate Limit

建议：

```text
120 queue requests / minute / support user
```

完整 Enforcement 可在 Phase 09 强化，但 Contract、Metric 和 Error Code 现在冻结。

---

# 26. Tests First

## Application

```text
QuerySupportQueueApplicationServiceTest
SupportQueueFilterValidationTest
SupportQueuePageSizeTest
SupportQueueSortRankingTest
```

## Authorization

```text
SupportQueueRoleTest
SupportQueueScopeAuthorizationIT
SupportQueueFilterScopeTest
SupportQueueUnassignedAuthorizationTest
SupportQueueCrossTenantIsolationIT
```

## Cursor

```text
SupportQueueCursorCodecTest
SupportQueueCursorSignatureTest
SupportQueueCursorExpiryTest
SupportQueueCursorFilterBindingTest
SupportQueueCursorScopeBindingTest
SupportQueueCursorActorBindingTest
SupportQueueCursorSortVersionTest
SupportQueueEvaluationTimeTest
```

## API

```text
SupportQueueControllerTest
SupportQueueEmptyResponseTest
SupportQueueErrorContractTest
```

## PostgreSQL

```text
SupportQueueProjectionIT
SupportQueueDefaultStateFilterIT
SupportQueueFilterIT
SupportQueueStableSortIT
SupportQueueKeysetPaginationIT
SupportQueueQueryPlanIT
```

## Privacy / Non-mutation

```text
SupportQueueFieldVisibilityTest
SupportQueueResponseRedactionTest
SupportQueueTelemetryRedactionTest
SupportQueueDoesNotMutateTicketIT
SupportQueueDoesNotCreateOutboxIT
```

---

# 27. Package Mapping

```text
ticket.api.support
├── SupportTicketQueryController
├── SupportQueueResponse
├── SupportTicketSummaryResponse
├── SupportQueuePageResponse
└── SupportQueueApiMapper

ticket.application.port.in
└── QuerySupportQueueUseCase

ticket.application.query
├── SupportQueueQuery
├── SupportQueueFilters
├── SupportQueueResult
├── SupportTicketSummary
├── SupportQueueCursor
├── SupportQueueScope
└── SupportQueueSortVersion

ticket.application.service
└── QuerySupportQueueApplicationService

ticket.application.port.out
├── SupportQueueQueryPort
└── SupportQueueScopePort

ticket.infrastructure.query
├── JdbcSupportQueueQueryAdapter
├── SupportQueueProjection
├── SupportQueueCursorCodec
├── SupportQueueCursorSigner
└── SupportQueueQuerySql
```

---

# 28. Definition of Done

- [ ] 只有批准的 Support 角色可以调用。
- [ ] `tickets:read:queue` 强制执行。
- [ ] Queue Scope 来自可信上下文。
- [ ] Authorization Predicate 下推到 SQL。
- [ ] Filter 必须是 Scope 子集。
- [ ] 默认只返回非终态 Ticket。
- [ ] 默认排序和 Rank 已冻结。
- [ ] Cursor 使用 Keyset Pagination。
- [ ] Cursor 绑定 Actor、Scope、Filter、Sort 和 Evaluation Time。
- [ ] 权限变化使旧 Cursor 失效。
- [ ] Empty Queue 返回 200。
- [ ] Summary 不包含 Description、Message、Email 或 Secret。
- [ ] 无 Offset、Count Query 和 N+1。
- [ ] Query 不修改 Ticket。
- [ ] Query 不创建 Outbox。
- [ ] Query Plan 达到基线。
- [ ] JSON Schema、Security、Cursor 和 Redaction Test 通过。
- [ ] PostgreSQL、ArchUnit、`./mvnw clean verify` 和 CI 通过。
- [ ] Traceability 更新。

---

# 29. 实现后保证

```text
IT Support 可以快速查看其授权范围内最需要处理的 Ticket，
不能越权访问其他 Queue、Tenant、Team 或敏感数据；
排序和分页具有确定规则，SLA 时间计算在同一分页会话中稳定，
并且查询不会修改 Ticket 生命周期。
```
