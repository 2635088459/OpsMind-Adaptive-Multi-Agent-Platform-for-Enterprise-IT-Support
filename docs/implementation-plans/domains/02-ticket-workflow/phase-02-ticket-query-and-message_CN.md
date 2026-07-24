# OpsMind Ticket Workflow — Phase 02 Ticket Query and Message Slice

> **文档编号：** IMP-TW-P02  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 02  
> **阶段名称：** Ticket Query and Message Slice  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **前置条件：** Phase 01 Create Ticket Vertical Slice Exit Criteria 已通过  
> **主要 Feature Specs：**
>
> - `SPEC-TW-002-get-ticket`
> - `SPEC-TW-003-list-requester-tickets`
> - `SPEC-TW-004-add-ticket-message`
> - `SPEC-TW-005-support-queue-query`
> - `SPEC-TW-006-ticket-timeline`
>
> **代码目录：** `services/ticket-workflow-service/`  
> **Spec 目录：** `docs/specs/domains/02-ticket-workflow/`  
> **Traceability：** `docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml`

---

# 1. 阶段目标

Phase 02 的目标是在 Phase 01 已经能够创建 Ticket 的基础上，交付 Ticket 的核心查询和消息能力。

本阶段完成后，系统应支持：

```text
Employee
→ 查看自己的一张 Ticket
→ 查看自己的 Ticket 列表
→ 向自己的 Ticket 添加公开消息
→ 查看公开 Timeline

IT Support
→ 查看自己有权限访问的 Support Queue
→ 查看有权限的 Ticket
→ 添加公开消息或内部消息
→ 查看包含内部信息的 Support Timeline
```

本阶段建立：

- Ticket Read Model
- Resource Ownership Authorization
- Support Queue Authorization
- Cursor Pagination
- Public / Internal Message Visibility
- Append-only Message Model
- Timeline Projection
- Query-side Performance Baseline
- Query and Message Audit
- Query and Message Observability

---

# 2. 为什么 Phase 02 紧接 Phase 01

Phase 01 只解决：

```text
Employee 可以可靠创建一张 Ticket
```

但系统还不能：

- 查看刚创建的 Ticket。
- 查看 Ticket 列表。
- 添加补充信息。
- 让 Support 查看待处理队列。
- 查看完整操作时间线。

后续 Phase 03 的 Triage 和 Agent Workflow 需要稳定的查询和消息能力，因为：

- Agent 需要读取 Ticket 上下文。
- Employee 需要补充信息。
- IT Support 需要查看队列。
- Timeline 需要承载后续状态、消息、审批和工具执行记录。

因此 Phase 02 是 Create Ticket 与自动化 Workflow 之间的必要桥梁。

---

# 3. 前置条件

进入 Phase 02 前必须满足：

- Phase 00 已完成。
- Phase 01 已通过 Exit Review。
- `SPEC-TW-001-create-ticket` 已完成。
- `POST /api/v1/tickets` 已可用。
- Ticket、Resolution Cycle、SLA Cycle、History、Audit、Outbox 和 Idempotency 已原子提交。
- Ticket Identity 和 Display ID 已冻结。
- Security Principal Mapping 已可用。
- Error Envelope 已可用。
- PostgreSQL Testcontainer 已可用。
- ArchUnit 和 CI 已可用。
- `./mvnw clean verify` 已通过。

---

# 4. Phase 02 Feature Spec 划分

Phase 02 包含五个 Feature Spec：

```text
SPEC-TW-002 Get Ticket
SPEC-TW-003 List Requester Tickets
SPEC-TW-004 Add Ticket Message
SPEC-TW-005 Support Queue Query
SPEC-TW-006 Ticket Timeline
```

推荐实现顺序：

```text
SPEC-TW-002
→ SPEC-TW-003
→ SPEC-TW-004
→ SPEC-TW-005
→ SPEC-TW-006
```

原因：

1. 先建立单 Ticket Read Model。
2. 再建立 Requester List Query。
3. 再加入 Message 写入能力。
4. 再建立 Support Queue。
5. 最后组合 Ticket、History、Message、Audit Projection 形成 Timeline。

每个 Spec 独立执行：

```text
Spec Review
→ RED
→ GREEN
→ REFACTOR
→ VERIFY
→ Traceability Update
```

---

# 5. 设计引用

Phase 02 主要引用：

## `01-domain-model`

用于：

- Ticket Identity
- Requester Ownership
- Message Entity
- Message Visibility
- Assignment / Queue fields
- Ticket Summary
- Timeline Item identity

## `02-business-invariants`

至少引用：

- Resource Ownership
- Message append-only
- Internal message visibility
- Ticket terminal-state message rules
- Query field visibility
- Pagination consistency
- Timeline ordering
- Audit requirements

每个 Feature Spec 必须列出精确 BI ID。

## `03-state-machine`

Phase 02 不新增主要状态转换，但必须遵守：

- 不允许 Query 触发状态修改。
- Add Message 不得通过通用状态字段修改 Ticket。
- Terminal State 对消息写入的规则必须明确。
- `WAITING_FOR_USER` 的自动恢复行为不属于 Phase 02，留给 Phase 04。

## `04-use-cases`

Phase 02 对应的 Use Case 应从 Document 04 中映射到：

```text
Get Ticket
List Requester Tickets
Add Message
Support Queue Query
Get Timeline
```

最终 Spec 必须使用已冻结的 UC ID。

## `05-api-contracts`

实现：

```text
GET /api/v1/tickets/{ticketId}
GET /api/v1/tickets
POST /api/v1/tickets/{ticketId}/messages
GET /api/v1/support/tickets
GET /api/v1/tickets/{ticketId}/timeline
```

实际路径以 API Contract 中的冻结路径为准。

## `06-event-contracts`

Phase 02 可能发布：

```text
ticket.message.added.v1
```

如果 LLD 已冻结对应事件，则写入 Outbox。

纯 Query 不发布业务事件。

## `07-data-model`

实现或扩展：

```text
ticket.ticket_messages
query indexes
support queue indexes
timeline query indexes
```

复用：

```text
ticket.tickets
ticket.ticket_status_history
ticket.audit_records
ticket.outbox_events
```

## `08-transaction-and-outbox`

Add Message 事务至少包含：

```text
Message
+ Required Audit
+ Optional Outbox Event
```

Query 不开启不必要的写事务。

## `09-concurrency-and-idempotency`

Add Message 需要：

- `Idempotency-Key`
- Same Key / Same Payload Replay
- Same Key / Different Payload Conflict
- Concurrent Duplicate Prevention

GET Query 天然幂等，不需要 Idempotency Key。

## `10-error-handling-and-reconciliation`

实现：

- Ticket Not Found
- Resource Hidden as Not Found
- Forbidden Queue Access
- Invalid Cursor
- Invalid Message
- Message Conflict
- Terminal-state write rejection
- Safe Query Failure

## `11-security-and-authorization`

实现：

- Requester Ownership
- Support Queue Authorization
- Public / Internal Field Visibility
- Internal Message Permission
- Sensitive Read Audit
- No Cross-user Read

## `12-observability-and-audit`

实现：

- Read Counter
- Query Duration
- Queue Query Duration
- Message Add Counter
- Authorization Denied Counter
- Sensitive Read Audit
- Internal Message Audit
- No high-cardinality metric labels

## `13-package-and-class-design`

实现：

- Query API Adapter
- Query Application Service
- Query Port
- JDBC Projection Adapter
- Message Command Service
- Message Domain Model
- Message Persistence Adapter
- Timeline Projection Service

## `14-testing-strategy`

实现：

- Query Unit / Slice Test
- Security Test
- PostgreSQL Integration Test
- Pagination Test
- Visibility Test
- Message Atomicity Test
- Timeline Ordering Test
- Contract Test
- Performance Baseline Test

---

# 6. Scope

Phase 02 包含：

- Get Ticket by ID
- List Requester Tickets
- Add Public Requester Message
- Add Public Support Message
- Add Internal Support Note
- Support Queue Query
- Ticket Timeline Query
- Public / Internal field visibility
- Cursor pagination
- Stable sorting
- Message append-only
- Message idempotency
- Query audit where required
- Message business audit
- Message Outbox event where required
- Query and message telemetry
- Query and message tests

---

# 7. Non-goals

Phase 02 不实现：

- Agent Triage
- Ticket Classification
- Waiting for User workflow
- Approval
- Tool Execution
- Verification
- Resolution
- Close / Reopen / Cancel
- Assignment mutation
- Escalation mutation
- Full-text search
- Semantic search
- Attachment upload
- Message edit
- Message delete
- Email ingestion
- Notification delivery
- WebSocket live updates
- Generic analytics dashboard
- Cross-domain memory retrieval

Phase 02 可以展示 Assignment / Queue 字段，但不实现完整 Assignment Command；该能力属于后续生命周期阶段。

---

# 8. Query Architecture

Phase 02 采用轻量 CQRS：

```text
Command Side
→ Domain Aggregate
→ JPA / Persistence Adapter

Query Side
→ Query Service
→ JDBC Projection
→ DTO
```

原则：

- Query 不需要重建完整 Ticket Aggregate。
- Query 不通过 JPA Lazy Graph 拼装大型对象。
- Query 使用明确 SQL 或 JDBC Projection。
- Query DTO 与 Domain Object 分离。
- Query 只能读取当前 Actor 被授权查看的字段。
- Query Filter 和 Authorization 条件应尽量下推到 SQL。
- 不先查出数据再在内存中过滤敏感记录。

---

# 9. SPEC-TW-002 — Get Ticket

## 9.1 目标

允许授权 Actor 查看一张 Ticket 的当前详情。

## 9.2 Actors

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR with approved scope
```

## 9.3 Authorization

Employee：

```text
ticket.requesterId == principal.subject
```

Support：

```text
principal has access to the Ticket queue / application / assignment scope
```

未授权 Employee 访问他人 Ticket 时，建议返回：

```text
404 TICKET_NOT_FOUND
```

以避免资源枚举。

Support 无队列权限时，根据 Security Contract 返回：

```text
403 FORBIDDEN
```

或隐藏为 `404`，最终由 Spec 冻结。

## 9.4 Response

Employee View 允许：

- TicketId
- DisplayId
- Title
- Description
- ApplicationCode
- Status
- Priority
- Public assignment label
- CreatedAt
- UpdatedAt
- Version
- Public latest message summary
- SLA summary where allowed

Employee View 禁止：

- Internal messages
- Internal notes
- Risk score
- Security flags
- Approval internals
- Tool credentials
- Internal actor identifiers
- Reconciliation metadata

Support View 可包含更多内部字段，但必须按 Scope 控制。

## 9.5 Query Consistency

默认：

```text
Read Committed
```

Response 应返回：

```text
ETag = current version
```

Get Ticket 不修改 `updatedAt`。

---

# 10. SPEC-TW-003 — List Requester Tickets

## 10.1 目标

允许 Employee 查看自己创建的 Ticket 列表。

## 10.2 Endpoint

建议：

```text
GET /api/v1/tickets
```

## 10.3 Filters

Phase 02 MVP 支持有限 Filter：

```text
status
applicationCode
createdFrom
createdTo
```

不支持任意动态 Query Language。

## 10.4 Sorting

默认：

```text
createdAt DESC, ticketId DESC
```

允许的 Sort 必须白名单化。

## 10.5 Cursor Pagination

使用：

```text
opaque cursor
```

Cursor 至少编码：

```text
lastCreatedAt
lastTicketId
filter fingerprint
sort version
```

规则：

- Cursor 不暴露原始 SQL。
- Cursor 必须签名或防篡改。
- Cursor 与当前 Filter 不匹配时返回 `INVALID_CURSOR`。
- 不使用 Offset 作为主要大列表分页方式。
- Page Size 有上限，例如 50。
- 默认 Page Size 例如 20。

## 10.6 Ownership

SQL 必须包含：

```text
requester_id = principal.subject
```

不能依赖应用内存过滤。

---

# 11. SPEC-TW-004 — Add Ticket Message

## 11.1 目标

允许 Employee 和 IT Support 向 Ticket 追加消息。

## 11.2 Message Types

```text
PUBLIC_REQUESTER_MESSAGE
PUBLIC_SUPPORT_MESSAGE
INTERNAL_SUPPORT_NOTE
```

Employee 只能创建：

```text
PUBLIC_REQUESTER_MESSAGE
```

IT Support 可以创建：

```text
PUBLIC_SUPPORT_MESSAGE
INTERNAL_SUPPORT_NOTE
```

## 11.3 Visibility

```text
PUBLIC
INTERNAL
```

Employee 不得：

- 指定 `INTERNAL`
- 创建 Internal Note
- 查看 Internal Note
- 通过未知字段注入 Visibility

## 11.4 Append-only

Message 创建后：

- 不支持 Update。
- 不支持 Delete。
- 不覆盖原内容。
- 更正通过追加新 Message 完成。
- 应用账号不执行普通 UPDATE / DELETE。

## 11.5 Request

建议：

```json
{
  "content": "I restarted the VPN client, but the error still appears.",
  "messageType": "PUBLIC_REQUESTER_MESSAGE"
}
```

客户端不得提供：

```text
messageId
authorId
authorType
visibility
createdAt
ticketVersion
internalMetadata
```

Employee 的 `messageType` 可以由 Endpoint 隐式决定，以进一步减少 Mass Assignment。

## 11.6 Validation

- Content 不能为空。
- 长度上限必须冻结。
- 禁止 Secret。
- 安全展示处理。
- 不能只包含空白。
- 不直接信任 HTML。
- Attachment 不在本 Phase。

## 11.7 State Rules

Phase 02 只追加消息，不自动改变 Ticket 状态。

特别是：

```text
WAITING_FOR_USER + requester reply
```

自动恢复 Workflow 属于 Phase 04。

对于 Terminal State：

- `CLOSED`：默认拒绝普通 Message，要求先 Reopen。
- `CANCELLED`：拒绝 Message。
- `FAILED` / `ESCALATED`：根据 LLD 冻结读写规则。
- `RESOLVED`：可以允许 Requester 追加反馈，但不得在 Phase 02 自动 Reopen。

## 11.8 Idempotency

Message Command 要求：

```text
Idempotency-Key
```

相同 Actor、Ticket、Key、Payload：

```text
return original Message
```

不同 Payload：

```text
409 IDEMPOTENCY_KEY_REUSED
```

## 11.9 Transaction

同一事务：

```text
Insert Message
Insert Business Audit
Insert ticket.message.added Outbox Event when required
Complete Idempotency Record
```

Message 不应无理由更新 Ticket Aggregate Version。

如果设计要求 `lastActivityAt` 或 `updatedAt` 更新，则必须：

- 在 Spec 中明确。
- 使用乐观锁或安全 SQL。
- 不将 Message 创建伪装成 Ticket 状态转换。

---

# 12. SPEC-TW-005 — Support Queue Query

## 12.1 目标

允许 IT Support 查看其有权处理的 Ticket Queue。

## 12.2 Authorization Dimensions

可能包含：

```text
applicationCode
supportTeamId
assignmentGroup
region
tenant
sensitivity
role
```

最终 Filter 以 Security LLD 为准。

## 12.3 Default Queue

建议默认显示：

```text
non-terminal Tickets
within authorized support scope
ordered by priority and SLA urgency
```

默认排序建议：

```text
SLA breach state
priority
createdAt
ticketId
```

必须使用稳定的 Tie-breaker。

## 12.4 Filters

MVP 可支持：

```text
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

Filter 必须：

- 白名单化。
- 参数化 SQL。
- 有索引支持。
- 不允许任意字段排序。

## 12.5 Field Visibility

Support Queue Summary 不应返回：

- 完整 Description
- 全部 Messages
- Secret
- Credential
- Raw requester attributes unnecessary for triage

使用最小摘要字段。

## 12.6 Sensitive Read Audit

根据字段和角色，Support Queue Query 可能只记 Aggregate Metric；打开单张 Ticket 的敏感详情需要 Audit。

不能为每个 Queue Row 生成高成本 Audit Record，除非 Policy 明确要求。

---

# 13. SPEC-TW-006 — Ticket Timeline

## 13.1 目标

提供按时间排序的 Ticket 事件视图。

Timeline 可能组合：

```text
Ticket Status History
Public Messages
Internal Notes
Assignment History
Approval Summary
Tool Summary
Verification Summary
Audit-safe Business Actions
```

Phase 02 实际已有的数据主要是：

```text
Initial Status History
Public Messages
Internal Notes
Ticket Created summary
```

未来 Phase 产生的新 Timeline Item 可以通过同一 Projection 扩展。

## 13.2 Views

Employee Timeline：

```text
PUBLIC items only
```

Support Timeline：

```text
PUBLIC + authorized INTERNAL items
```

Auditor Timeline：

```text
Policy-approved audit view
```

## 13.3 Ordering

稳定排序：

```text
occurredAt ASC
itemTypeOrder ASC
itemId ASC
```

或倒序，但必须冻结并保持稳定。

## 13.4 Pagination

Timeline 使用 Cursor Pagination。

Cursor 包含：

```text
occurredAt
itemTypeOrder
itemId
view type
```

## 13.5 Projection

每个 Item 统一为：

```text
itemId
itemType
visibility
occurredAt
actor summary
safe summary
related version
```

不要把完整 Audit Metadata 直接暴露给 Employee。

---

# 14. Data Model Changes

Phase 02 至少新增：

```text
ticket.ticket_messages
```

建议字段：

```text
message_id
ticket_id
message_type
visibility
author_type
author_id
content
content_format
created_at
source_command_id
source_event_id
trace_id
data_classification
```

约束：

- PK `message_id`
- FK `ticket_id`
- Message Type Check
- Visibility Check
- Content length Check
- CreatedAt required
- Append-only privilege
- Optional unique command correlation

可能新增：

```text
ticket.message_idempotency linkage
```

或复用通用：

```text
ticket.idempotency_records
```

---

# 15. Index Strategy

至少评估：

## Requester List

```text
(requester_id, created_at DESC, ticket_id DESC)
```

## Ticket Message

```text
(ticket_id, created_at ASC, message_id ASC)
```

## Support Queue

根据实际 Filter 组合建立部分索引，例如：

```text
(status, application_code, priority, created_at, ticket_id)
```

以及：

```text
assigned_team_id
assigned_agent_id
sla_state
```

禁止一次创建大量猜测索引。

所有索引必须由：

- Query Plan
- Integration Test
- Explain Analyze
- 实际 Query Pattern

证明需要。

---

# 16. Security and Field Visibility

## Employee

可以：

- 查看自己的 Ticket。
- 查看自己的 Ticket 列表。
- 添加 Public Requester Message。
- 查看 Public Timeline。

不能：

- 查看他人 Ticket。
- 查看 Internal Note。
- 查看 Support Queue。
- 伪造 Author。
- 指定 Internal Visibility。

## IT Support

需要：

```text
tickets:read:queue
tickets:message:public
tickets:message:internal
```

或设计中批准的等效 Scope。

Support 权限必须同时满足资源范围。

## IT Admin / Manager / Auditor

根据批准 Scope 和 Field Policy 提供不同 View。

## Resource Hiding

对 Employee 的跨用户资源访问，优先返回：

```text
404 TICKET_NOT_FOUND
```

避免 Ticket ID 枚举。

---

# 17. Audit Requirements

至少 Audit：

```text
TICKET_VIEWED_SENSITIVE
TICKET_MESSAGE_ADDED
TICKET_INTERNAL_NOTE_ADDED
SUPPORT_QUEUE_ACCESSED when policy requires
TICKET_TIMELINE_VIEWED when sensitive
```

Audit 内容不得保存完整 Message Content。

Message Audit 记录：

```text
actor
ticketId
messageId
messageType
visibility
traceId
commandId
outcome
occurredAt
```

Query Audit 应控制成本，不应为普通 Requester List 每一行生成 Audit。

---

# 18. Observability

## Metrics

建议：

```text
opsmind_ticket_query_total
opsmind_ticket_query_duration_seconds
opsmind_ticket_query_not_found_total
opsmind_ticket_query_authorization_denied_total
opsmind_ticket_list_total
opsmind_ticket_list_duration_seconds
opsmind_ticket_message_add_total
opsmind_ticket_message_add_duration_seconds
opsmind_ticket_message_replay_total
opsmind_ticket_support_queue_query_total
opsmind_ticket_support_queue_query_duration_seconds
opsmind_ticket_timeline_query_total
opsmind_ticket_timeline_query_duration_seconds
```

## Bounded Labels

```text
operation
actor_type
view_type
result
status_class
message_type
visibility
```

禁止：

```text
ticketId
messageId
requesterId
cursor
idempotencyKey
```

## Trace

至少：

```text
GetTicketUseCase
ListRequesterTicketsUseCase
AddTicketMessageUseCase
QuerySupportQueueUseCase
GetTicketTimelineUseCase
```

## Logging

记录安全摘要，不记录：

- Message Content
- Full Description
- Cursor raw payload
- JWT
- Idempotency Key

---

# 19. TDD 执行顺序

## 19.1 SPEC-TW-002

```text
Spec Review
→ GetTicketSecurityTest RED
→ GetTicketQueryTest RED
→ JDBC Projection GREEN
→ Field Visibility Test
→ Verify
```

## 19.2 SPEC-TW-003

```text
Spec Review
→ Ownership Query RED
→ Cursor Pagination RED
→ Stable Sorting GREEN
→ Invalid Cursor Test
→ Verify
```

## 19.3 SPEC-TW-004

```text
Spec Review
→ Message Domain RED
→ Message Authorization RED
→ Message Persistence RED
→ Atomicity RED
→ Idempotency RED
→ API GREEN
→ Event Contract
→ Verify
```

## 19.4 SPEC-TW-005

```text
Spec Review
→ Queue Authorization RED
→ Filter and Sort RED
→ Projection GREEN
→ Query Plan Review
→ Verify
```

## 19.5 SPEC-TW-006

```text
Spec Review
→ Visibility RED
→ Ordering RED
→ Cursor RED
→ Timeline Projection GREEN
→ Verify
```

---

# 20. Test Inventory

## SPEC-TW-002

```text
GetTicketApplicationServiceTest
GetTicketControllerTest
GetTicketRequesterOwnershipTest
GetTicketSupportAuthorizationTest
GetTicketFieldVisibilityTest
GetTicketQueryIT
```

## SPEC-TW-003

```text
ListRequesterTicketsControllerTest
ListRequesterTicketsOwnershipIT
ListRequesterTicketsCursorIT
ListRequesterTicketsStableSortIT
ListRequesterTicketsInvalidCursorTest
```

## SPEC-TW-004

```text
TicketMessageTest
AddTicketMessageApplicationServiceTest
AddRequesterMessageSecurityTest
AddSupportMessageSecurityTest
AddInternalNoteSecurityTest
AddTicketMessageControllerTest
AddTicketMessagePersistenceIT
AddTicketMessageAtomicityIT
AddTicketMessageIdempotencyIT
AddTicketMessageConcurrentIdempotencyIT
TicketMessageAddedEventContractTest
TicketMessageRedactionTest
```

## SPEC-TW-005

```text
SupportQueueControllerTest
SupportQueueAuthorizationTest
SupportQueueFilterIT
SupportQueuePaginationIT
SupportQueueStableSortIT
SupportQueueQueryPlanIT
SupportQueueFieldVisibilityTest
```

## SPEC-TW-006

```text
TicketTimelineControllerTest
TicketTimelineRequesterVisibilityTest
TicketTimelineSupportVisibilityTest
TicketTimelineOrderingIT
TicketTimelineCursorIT
TicketTimelineProjectionIT
```

## Cross-cutting

```text
LayerDependencyTest
QueryTelemetryTest
MessageTelemetryTest
SensitiveReadAuditIT
```

---

# 21. 推荐 Package Mapping

```text
ticket.api.publicapi
├── PublicTicketQueryController
├── PublicTicketMessageController
├── GetTicketResponse
├── TicketSummaryResponse
├── AddTicketMessageRequest
├── AddTicketMessageResponse
└── TicketTimelineResponse

ticket.api.support
├── SupportTicketQueryController
├── SupportTicketMessageController
├── SupportQueueResponse
└── SupportTicketTimelineResponse

ticket.application.port.in
├── GetTicketUseCase
├── ListRequesterTicketsUseCase
├── AddTicketMessageUseCase
├── QuerySupportQueueUseCase
└── GetTicketTimelineUseCase

ticket.application.query
├── GetTicketQuery
├── ListRequesterTicketsQuery
├── SupportQueueQuery
└── TicketTimelineQuery

ticket.application.command
├── AddTicketMessageCommand
└── AddTicketMessageResult

ticket.application.service
├── GetTicketApplicationService
├── ListRequesterTicketsApplicationService
├── AddTicketMessageApplicationService
├── QuerySupportQueueApplicationService
└── GetTicketTimelineApplicationService

ticket.application.port.out
├── TicketQueryPort
├── TicketMessageRepository
├── TicketTimelineQueryPort
├── AuditRecordPort
├── OutboxEventRepository
└── IdempotencyRepository

ticket.domain.message
├── TicketMessage
├── TicketMessageId
├── TicketMessageType
├── MessageVisibility
├── MessageContent
└── TicketMessageAdded

ticket.infrastructure.query
├── JdbcTicketQueryAdapter
├── JdbcSupportQueueQueryAdapter
├── JdbcTicketTimelineQueryAdapter
└── CursorCodec

ticket.infrastructure.persistence
├── TicketMessageJpaEntity
├── TicketMessageSpringDataRepository
├── TicketMessagePersistenceMapper
└── TicketMessagePersistenceAdapter
```

---

# 22. Implementation Tasks

## P02-T01 Review Phase 02 Scope

确认五个 Spec 和顺序。

## P02-T02 Write SPEC-TW-002

Get Ticket。

## P02-T03 Implement Get Ticket

Requester / Support Views。

## P02-T04 Write SPEC-TW-003

List Requester Tickets。

## P02-T05 Implement Requester List

Cursor、Filter、Stable Sort。

## P02-T06 Write SPEC-TW-004

Add Ticket Message。

## P02-T07 Add Message Migration

只创建 Message 所需表和索引。

## P02-T08 Implement Message Domain and Command

Public / Internal、Append-only、Idempotency。

## P02-T09 Add Message Event and Audit

Outbox、Redaction、Audit。

## P02-T10 Write SPEC-TW-005

Support Queue Query。

## P02-T11 Implement Support Queue

Authorization、Filters、Projection、Indexes。

## P02-T12 Write SPEC-TW-006

Ticket Timeline。

## P02-T13 Implement Timeline Projection

Visibility、Ordering、Cursor。

## P02-T14 Add Telemetry

Query 和 Message Metrics / Trace / Logs。

## P02-T15 Update Traceability

五个 Spec 对应代码和测试。

## P02-T16 Update README

Curl、Auth、Pagination、Visibility 和 Non-goals。

---

# 23. 推荐 Pull Request 划分

## PR 1 — Get Ticket

```text
docs(spec): define SPEC-TW-002 get ticket
test(query): add get ticket security and projection tests
feat(query): implement requester and support ticket views
```

## PR 2 — Requester List

```text
docs(spec): define SPEC-TW-003 requester ticket list
test(query): add ownership and cursor tests
feat(query): implement requester ticket list
```

## PR 3 — Ticket Message

```text
docs(spec): define SPEC-TW-004 add ticket message
test(message): add domain, security and atomicity tests
feat(message): implement append-only ticket messages
feat(outbox): persist ticket.message.added event
```

## PR 4 — Support Queue

```text
docs(spec): define SPEC-TW-005 support queue
test(query): add queue authorization and pagination tests
feat(query): implement support queue projection
```

## PR 5 — Timeline and Hardening

```text
docs(spec): define SPEC-TW-006 ticket timeline
test(timeline): add visibility and ordering tests
feat(timeline): implement ticket timeline projection
feat(observability): add Phase 02 telemetry
docs(traceability): complete Phase 02 mapping
```

---

# 24. Deliverables

## 文档

```text
phase-02-ticket-query-and-message_CN.md
phase-02-ticket-query-and-message_EN.md
SPEC-TW-002-get-ticket/
SPEC-TW-003-list-requester-tickets/
SPEC-TW-004-add-ticket-message/
SPEC-TW-005-support-queue-query/
SPEC-TW-006-ticket-timeline/
traceability-matrix.yaml
```

## 代码

```text
Ticket Query Services
Requester List Query
Ticket Message Domain
Message Persistence
Support Queue Query
Timeline Projection
Cursor Codec
Security Views
Audit
Outbox for Message
Telemetry
```

## 数据库

```text
ticket.ticket_messages
required query indexes
```

## 测试

```text
Query
Ownership
Visibility
Cursor
Stable Sort
Message Domain
Message Atomicity
Message Idempotency
Support Queue Authorization
Timeline Ordering
Contract
Telemetry
```

---

# 25. 风险与处理

## 风险 1：Query 通过重建完整 Aggregate

处理：

- Query Side 使用 JDBC Projection。
- Command Side 才使用 Aggregate。

## 风险 2：先查全部数据再做内存权限过滤

处理：

- Ownership 和 Queue Scope 下推到 SQL。

## 风险 3：Internal Note 泄漏给 Employee

处理：

- 分离 Public / Support DTO。
- Visibility 条件进入 Query。
- 增加 Redaction 和 Security Test。

## 风险 4：Cursor 不稳定

处理：

- 使用唯一 Tie-breaker。
- Cursor 绑定 Filter 和 Sort Version。
- 增加 Concurrent Insert Pagination Test。

## 风险 5：Message 创建偷偷改变 Ticket 状态

处理：

- Phase 02 不自动状态转换。
- `WAITING_FOR_USER` Resume 留给 Phase 04。

## 风险 6：Message 可被修改或删除

处理：

- Append-only Privilege。
- 不提供 Update / Delete API。
- 更正通过新消息。

## 风险 7：Support Queue 查询过慢

处理：

- 有限 Filter。
- 明确 Projection。
- Explain Analyze。
- 根据真实 Query 建索引。

## 风险 8：每个 Query 都写大量 Audit

处理：

- Sensitive Detail Read 才记录详细 Audit。
- List / Queue 使用聚合策略或 Policy 定义的低成本 Audit。

---

# 26. Exit Criteria

Phase 02 完成必须满足：

## Specs

- 五个 Feature Spec 已 Review。
- Scope 和 Non-goals 已冻结。
- API、Security、Pagination、Visibility 规则完整。

## Get Ticket

- Employee 只能读取自己的 Ticket。
- Support 只能读取授权 Queue 内的 Ticket。
- Employee 看不到 Internal 字段。
- ETag 与 Ticket Version 一致。
- 未授权跨用户访问不会泄漏资源存在性。

## Requester List

- SQL 强制 Requester Ownership。
- Cursor Pagination 稳定。
- Filter 和 Cursor 不匹配时被拒绝。
- Page Size 有上限。
- 不使用无界查询。

## Message

- Employee 只能创建 Public Requester Message。
- Support Internal Note 需要显式 Scope。
- Message Append-only。
- Message Content Validation 通过。
- Message Idempotency 通过。
- Concurrent Duplicate 不产生重复 Message。
- Message、Audit、Outbox 和 Idempotency 原子提交。
- Terminal-state Message 规则通过。

## Support Queue

- Queue Authorization 下推到查询。
- Filter 白名单化。
- Stable Sort 通过。
- Sensitive 字段最小化。
- Query Plan 满足基线。

## Timeline

- Employee Timeline 只显示 Public Item。
- Support Timeline 显示授权 Internal Item。
- Ordering 稳定。
- Cursor 稳定。
- 后续 Timeline Item 类型可扩展。

## Security

- Resource Ownership Test 通过。
- Internal Visibility Test 通过。
- Support Queue Authorization Test 通过。
- Sensitive Read Audit 符合 Policy。
- 无 Mass Assignment。

## Observability

- Query 和 Message Metrics 正确。
- 无高基数 Label。
- Log 不包含 Message Content、Description、JWT 或 Cursor Payload。
- Trace 可关联 API 和 DB Query。

## Quality

```text
./mvnw clean verify
```

通过。

- PostgreSQL Integration Test 通过。
- ArchUnit 通过。
- Secret Scan 通过。
- CI 通过。
- Docker Image 可启动。
- Traceability 已更新。
- README 已更新。

---

# 27. Exit Review Checklist

- [ ] Phase 01 已完成。
- [ ] SPEC-TW-002 已完成。
- [ ] SPEC-TW-003 已完成。
- [ ] SPEC-TW-004 已完成。
- [ ] SPEC-TW-005 已完成。
- [ ] SPEC-TW-006 已完成。
- [ ] Employee Ownership 已强制。
- [ ] Support Queue Scope 已强制。
- [ ] Public / Internal Visibility 已分离。
- [ ] Cursor Pagination 稳定。
- [ ] Message Append-only。
- [ ] Message Idempotency 通过。
- [ ] Message Atomicity 通过。
- [ ] Message Event Contract 通过。
- [ ] Timeline Ordering 通过。
- [ ] Query Plan 已 Review。
- [ ] Sensitive Read Audit 已验证。
- [ ] Telemetry Redaction Test 通过。
- [ ] ArchUnit 通过。
- [ ] `./mvnw clean verify` 通过。
- [ ] CI 通过。
- [ ] Traceability 已更新。
- [ ] README 已更新。

---

# 28. Phase 02 完成后允许做什么

通过 Exit Review 后进入：

```text
Phase 03 — Triage and Investigation Slice
```

Phase 03 可以安全依赖：

- Ticket Query
- Ticket Context
- Public Message
- Internal Note
- Support Queue
- Timeline
- Resource Authorization
- Query Projection
- Existing Audit / Trace Context

下一批 Feature Specs：

```text
SPEC-TW-007-start-triage
SPEC-TW-008-complete-classification
SPEC-TW-009-agent-workflow-failure
```

---

# 29. Definition of Done

Phase 02 完成意味着：

```text
OpsMind 已从“只能创建 Ticket”
发展为“Employee 和 IT Support 能够在权限边界内
读取、列出、沟通并查看 Ticket 时间线”的可用业务系统，
并且所有查询、消息、可见性、幂等、审计和性能行为
都由 Feature Spec 和自动化测试保护。
```
