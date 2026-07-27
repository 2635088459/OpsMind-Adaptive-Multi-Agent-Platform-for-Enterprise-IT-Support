# SPEC-TW-003 — List Requester Tickets

> **Spec ID：** SPEC-TW-003  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **Actor：** EMPLOYEE  
> **API：** `GET /api/v1/tickets`  
> **依赖：** SPEC-TW-001、SPEC-TW-002  
> **状态：** Proposed for Review

---

# 1. 目的

定义当前已认证 Employee 查看自己创建的 Ticket 列表时的完整行为。

```text
Authenticated Employee
→ tickets:read:self
→ requester_id = principal.subject
→ approved filters
→ stable keyset pagination
→ bounded Ticket summaries
```

系统必须保证：

- Employee 只能看到自己的 Ticket。
- Ownership 在 SQL 中执行。
- Page Size 有上限。
- Cursor 防篡改并绑定 Filter、Sort 和 Actor Scope。
- 新 Ticket 插入不会造成后续页重复。
- Query 不修改 Ticket。
- Response 不包含完整 Description 或 Internal 字段。

---

# 2. Scope

包含：

- JWT Authentication
- `tickets:read:self`
- Requester Ownership
- `status` Filter
- `applicationCode` Filter
- `createdFrom` / `createdTo`
- Stable Sorting
- Cursor Pagination
- Empty List
- Response Schema
- Error Contract
- Query Telemetry
- PostgreSQL Query Plan Test

不包含：

- Support Queue
- Ticket Detail
- Messages
- Timeline
- Search
- Offset Pagination
- Total Count
- Ticket Mutation

---

# 3. HTTP Contract

```http
GET /api/v1/tickets
Authorization: Bearer <JWT>
Accept: application/json
```

Query Parameters：

```text
limit
cursor
status
applicationCode
createdFrom
createdTo
```

Response Headers：

```http
Cache-Control: private, no-store
Vary: Authorization
Content-Type: application/json
```

---

# 4. Authentication and Ownership

JWT 验证：

- Signature
- Issuer
- Audience
- Expiration
- Subject
- Token Type
- Environment

缺少或无效 JWT：

```text
401 UNAUTHENTICATED
```

需要：

```text
tickets:read:self
```

缺少 Scope：

```text
403 FORBIDDEN
```

SQL 必须包含：

```sql
WHERE requester_id = :principalSubject
```

禁止先读取其他用户数据再在 Java 中过滤。

---

# 5. Filter Contract

## `status`

允许一个或多个 TicketStatus，最多 10 个。非法值返回 `400 VALIDATION_ERROR`。

## `applicationCode`

允许：

```text
HOUSING_PORTAL
EMAIL
VPN
OTHER
```

## `createdFrom`

```text
created_at >= createdFrom
```

## `createdTo`

```text
created_at < createdTo
```

要求：

```text
createdFrom < createdTo
```

最大查询区间建议为 365 天。

Filter Fingerprint 前：

- Enum 排序并去重。
- 时间转换为 UTC。
- `limit` 和 `cursor` 不进入 Fingerprint。

---

# 6. Page Size

```text
default = 20
minimum = 1
maximum = 50
```

超出范围返回：

```text
400 VALIDATION_ERROR
```

---

# 7. Sorting

MVP 固定：

```text
createdAt DESC
ticketId DESC
```

SQL：

```sql
ORDER BY created_at DESC, ticket_id DESC
```

`ticketId` 是唯一 Tie-breaker。

MVP 不接受客户端自定义 Sort。

---

# 8. Cursor Pagination

使用 Keyset Pagination。

第一页没有 Cursor。

下一页 Cursor Payload 至少包含：

```json
{
  "version": 1,
  "lastCreatedAt": "2026-07-22T18:00:00Z",
  "lastTicketId": "018f0e00-1111-7111-8111-111111111111",
  "filterFingerprint": "sha256:...",
  "sort": "createdAt:desc,ticketId:desc",
  "issuedAt": "2026-07-25T18:00:00Z",
  "expiresAt": "2026-07-26T18:00:00Z"
}
```

格式建议：

```text
base64url(payload) + "." + base64url(HMAC-SHA-256(payload))
```

Cursor 必须：

- Opaque
- 防篡改
- 24 小时 TTL
- 绑定 Filter
- 绑定 Operation
- 绑定 Actor Scope
- 不记录到普通日志
- 不作为 Metric Label

下一页 Predicate：

```sql
AND (
  created_at < :lastCreatedAt
  OR (
    created_at = :lastCreatedAt
    AND ticket_id < :lastTicketId
  )
)
```

读取：

```text
limit + 1
```

用于判断 `hasMore`。

以下情况返回：

```text
400 INVALID_CURSOR
```

- Malformed Cursor
- Invalid Signature
- Expired Cursor
- Filter Mismatch
- Sort Mismatch
- Actor Mismatch
- Operation Mismatch

---

# 9. Stable Pagination

使用不可变 Sort Key：

```text
createdAt
ticketId
```

因此：

- 新创建的 Ticket 不会插入旧 Cursor 的后续页。
- 已返回 Ticket 不会在下一页重复。
- 相同 CreatedAt 使用 TicketId 确定顺序。

不使用可变字段：

```text
updatedAt
status
priority
```

作为默认 Cursor Sort Key。

---

# 10. Response Contract

```json
{
  "items": [
    {
      "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
      "displayId": "INC-2048",
      "title": "Cannot sign in to Housing Portal",
      "applicationCode": "HOUSING_PORTAL",
      "status": "NEW",
      "priority": "UNASSIGNED",
      "createdAt": "2026-07-23T16:30:00Z",
      "updatedAt": "2026-07-23T16:30:00Z",
      "version": 0
    }
  ],
  "page": {
    "limit": 20,
    "hasMore": true,
    "nextCursor": "opaque-signed-cursor"
  },
  "appliedFilters": {
    "status": [],
    "applicationCode": [],
    "createdFrom": null,
    "createdTo": null
  }
}
```

Summary 允许：

- TicketId
- DisplayId
- Title
- ApplicationCode
- Status
- Priority
- CreatedAt
- UpdatedAt
- Version

禁止：

- Description
- RequesterId
- Requester Email
- Internal Assignment
- Internal Note
- Workflow ID
- Approval / Tool / Audit Metadata
- Secret

---

# 11. Empty List

没有 Ticket 时返回：

```text
HTTP 200
items = []
hasMore = false
nextCursor = null
```

不返回 `404`。

---

# 12. Query Architecture

```text
PublicTicketQueryController
→ ListRequesterTicketsUseCase
→ ListRequesterTicketsApplicationService
→ RequesterTicketQueryPort
→ JdbcRequesterTicketQueryAdapter
→ PostgreSQL
```

规则：

- 使用 JDBC Projection。
- 参数化 SQL。
- 不重建 Aggregate。
- 不读取 Description。
- 不加载 Message 或 Timeline。
- 不执行写操作。
- 不调用远程服务。
- 无 N+1。
- 无 Count Query。
- 无 Offset Scan。

---

# 13. SQL Shape

```sql
SELECT
  ticket_id,
  display_id,
  title,
  application_code,
  status,
  priority,
  created_at,
  updated_at,
  version
FROM ticket.tickets
WHERE requester_id = :requesterId
  AND (:statusesEmpty OR status = ANY(:statuses))
  AND (:applicationsEmpty OR application_code = ANY(:applicationCodes))
  AND (:createdFrom IS NULL OR created_at >= :createdFrom)
  AND (:createdTo IS NULL OR created_at < :createdTo)
  AND (
    :cursorAbsent
    OR created_at < :lastCreatedAt
    OR (
      created_at = :lastCreatedAt
      AND ticket_id < :lastTicketId
    )
  )
ORDER BY created_at DESC, ticket_id DESC
LIMIT :limitPlusOne
```

---

# 14. Index Strategy

最低评估：

```sql
(requester_id, created_at DESC, ticket_id DESC)
```

根据实际 Filter 使用频率再评估：

```text
(requester_id, status, created_at DESC, ticket_id DESC)
(requester_id, application_code, created_at DESC, ticket_id DESC)
```

索引必须通过 Query Plan 和 `EXPLAIN (ANALYZE, BUFFERS)` 证明需要。

---

# 15. Errors

| 场景 | HTTP | Code |
|---|---:|---|
| Invalid limit/filter/date | 400 | `VALIDATION_ERROR` |
| Invalid or expired cursor | 400 | `INVALID_CURSOR` |
| Missing/invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing scope | 403 | `FORBIDDEN` |
| Rate limited | 429 | `RATE_LIMITED` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Cursor Error 不返回内部 Payload 或签名失败细节。

---

# 16. Audit and Security Logging

普通 Employee Self-list 默认不创建 Business Audit Row。

以下情况可以记录 Security Audit：

- Missing Scope
- Cursor Tampering
- Repeated Invalid Cursor
- Abuse / Rate Limit
- Cross-environment Token

Audit 和 Log 不保存 Raw Cursor。

---

# 17. Observability

Trace：

```text
HTTP GET /api/v1/tickets
ListRequesterTicketsUseCase
ticket.cursor.decode
ticket.cursor.validate
db.ticket.requester_list
ticket.cursor.encode
```

Metrics：

```text
opsmind_ticket_list_total
opsmind_ticket_list_duration_seconds
opsmind_ticket_list_result_count
opsmind_ticket_list_invalid_cursor_total
opsmind_ticket_list_authorization_denied_total
```

允许低基数 Labels：

```text
result
status_class
cursor_present
has_filters
```

禁止：

```text
requesterId
ticketId
raw cursor
title
JWT
```

---

# 18. Performance

目标：

```text
p95 < 300 ms
p99 < 1 s
```

要求：

- Page Size 最大 50。
- 一次主 Query。
- 无 Count Query。
- 无 N+1。
- 无 Offset。
- 不读取 Description。
- 使用 Requester + Sort Index。

---

# 19. Tests First

```text
ListRequesterTicketsApplicationServiceTest
ListRequesterTicketsFilterValidationTest
ListRequesterTicketsPageSizeTest
TicketListCursorCodecTest
TicketListCursorSignatureTest
TicketListCursorExpiryTest
TicketListCursorFilterBindingTest
TicketListCursorPrincipalBindingTest
ListRequesterTicketsControllerTest
ListRequesterTicketsOwnershipIT
ListRequesterTicketsCrossUserIsolationIT
ListRequesterTicketsProjectionIT
ListRequesterTicketsCursorIT
ListRequesterTicketsStableSortIT
ListRequesterTicketsConcurrentInsertIT
ListRequesterTicketsFilterIT
ListRequesterTicketsQueryPlanIT
ListRequesterTicketsResponseRedactionTest
ListRequesterTicketsDoesNotMutateIT
ListRequesterTicketsDoesNotCreateOutboxIT
```

---

# 20. Package Mapping

```text
ticket.api.publicapi
├── PublicTicketQueryController
├── RequesterTicketListResponse
├── RequesterTicketSummaryResponse
└── RequesterTicketListApiMapper

ticket.application.port.in
└── ListRequesterTicketsUseCase

ticket.application.query
├── ListRequesterTicketsQuery
├── RequesterTicketListResult
├── RequesterTicketSummary
├── TicketListFilters
└── TicketListCursor

ticket.application.service
└── ListRequesterTicketsApplicationService

ticket.application.port.out
└── RequesterTicketQueryPort

ticket.infrastructure.query
├── JdbcRequesterTicketQueryAdapter
├── TicketListCursorCodec
├── TicketListCursorSigner
└── RequesterTicketQuerySql
```

---

# 21. Definition of Done

- [ ] Employee 只能看到自己的 Ticket。
- [ ] Ownership 在 SQL。
- [ ] Page Size 1–50。
- [ ] 固定排序为 `createdAt DESC, ticketId DESC`。
- [ ] Cursor 使用 Keyset Pagination。
- [ ] Cursor 有签名、版本和过期时间。
- [ ] Cursor 绑定 Filter 和 Actor Scope。
- [ ] Empty List 返回 200。
- [ ] Stable Pagination Test 通过。
- [ ] Concurrent Insert Test 通过。
- [ ] Response 不包含 Description 或 Internal 字段。
- [ ] Query 不修改 Ticket。
- [ ] Query 不创建 Outbox。
- [ ] Query Plan 使用预期索引。
- [ ] 无 Offset、Count Query 和 N+1。
- [ ] JSON Schema Test 通过。
- [ ] Telemetry Redaction Test 通过。
- [ ] ArchUnit 和 `./mvnw clean verify` 通过。
- [ ] CI 和 Traceability 更新完成。

---

# 22. 实现后保证

```text
Employee 可以快速、稳定地浏览自己的 Ticket，
不能看到其他用户或内部数据；
Filter、排序和 Cursor 确定、受限且防篡改，
新 Ticket 的创建不会破坏后续分页结果。
```
