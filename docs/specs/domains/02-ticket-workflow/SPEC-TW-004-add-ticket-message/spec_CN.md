# SPEC-TW-004 — Add Ticket Message

> **Spec ID：** SPEC-TW-004  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **Actors：** EMPLOYEE、IT_SUPPORT、IT_ADMIN、IT_MANAGER  
> **API：** `POST /api/v1/tickets/{ticketId}/messages`  
> **依赖：** SPEC-TW-001、SPEC-TW-002  
> **发布事件：** `ticket.message.added.v1`

---

# 1. 目的

定义授权 Actor 向现有 Ticket 追加消息时必须满足的完整行为：

```text
Authentication
→ Resource Authorization
→ Actor-specific Validation
→ Ticket State Guard
→ Idempotency
→ Append-only Message
→ Audit
→ Transactional Outbox
→ 201 Response
```

系统必须保证：

- Employee 只能向自己的 Ticket 添加公开消息。
- Support 只能向授权 Ticket 添加公开消息或内部备注。
- Author、AuthorType 和 Visibility 由服务端决定。
- Message 不可修改、不可删除。
- 重复和并发请求不会创建重复 Message。
- Message、Audit、Outbox、Idempotency 原子提交。
- Phase 02 不因 Message 自动修改 Ticket 状态。
- Content 不进入 Event、Audit、Log 或 Trace。

---

# 2. Scope

包含：

- `PUBLIC_REQUESTER_MESSAGE`
- `PUBLIC_SUPPORT_MESSAGE`
- `INTERNAL_SUPPORT_NOTE`
- JWT 和 Scope
- Resource Authorization
- Content Validation
- Ticket State Guard
- Append-only Persistence
- Idempotency
- Business Audit
- Outbox Event
- Error Contract
- Observability
- Automated Tests

不包含：

- Message Edit/Delete
- Attachment
- Email Ingestion
- Notification Delivery
- 自动状态转换
- Waiting-for-user Resume
- 自动 Reopen
- Timeline Query
- Message Search
- Rich HTML

---

# 3. HTTP Contract

```http
POST /api/v1/tickets/{ticketId}/messages
Authorization: Bearer <JWT>
Idempotency-Key: <1-128 characters>
Content-Type: application/json
Accept: application/json
```

成功：

```http
HTTP 201 Created
Location: /api/v1/tickets/{ticketId}/messages/{messageId}
ETag: "0"
```

---

# 4. Actor-specific Request

## Employee

```json
{
  "content": "I restarted the VPN client, but the error still appears."
}
```

服务端强制：

```text
messageType = PUBLIC_REQUESTER_MESSAGE
visibility = PUBLIC
authorType = EMPLOYEE
authorId = principal.subject
```

Employee Schema 使用 `additionalProperties = false`，不能提交：

```text
messageType
visibility
authorId
authorType
messageId
createdAt
version
internalMetadata
```

## Support

公开消息：

```json
{
  "content": "The account has been unlocked. Please try again.",
  "messageType": "PUBLIC_SUPPORT_MESSAGE"
}
```

内部备注：

```json
{
  "content": "Identity verification is still required.",
  "messageType": "INTERNAL_SUPPORT_NOTE"
}
```

服务端映射：

```text
PUBLIC_SUPPORT_MESSAGE → PUBLIC
INTERNAL_SUPPORT_NOTE  → INTERNAL
```

Support 不能直接提交 `visibility`。

---

# 5. Authentication and Authorization

Employee 需要：

```text
tickets:message:self
ticket.requesterId = principal.subject
```

Support Public Message 需要：

```text
tickets:message:public
```

Internal Note 需要：

```text
tickets:message:internal
```

Support 还必须满足 Queue、Application 或 Team Resource Scope。

缺少总体 Scope：

```text
403 FORBIDDEN
```

Ticket 不存在或不在资源权限内：

```text
404 TICKET_NOT_FOUND
```

避免泄漏 Ticket 是否存在。

---

# 6. Message Types and Visibility

```text
PUBLIC_REQUESTER_MESSAGE → PUBLIC
PUBLIC_SUPPORT_MESSAGE   → PUBLIC
INTERNAL_SUPPORT_NOTE    → INTERNAL
```

客户端不能自定义 Type 或 Visibility。

Internal Note：

- 不出现在 Employee API。
- 不出现在 Employee Timeline。
- 不进入 Public Notification。
- 只对批准的 Support / Auditor View 可见。

---

# 7. Content Validation

规则：

```text
trim 后 1–8000 字符
UTF-8
不能只包含空白
禁止危险控制字符
HTML 不可信
不执行 Script
禁止 SECRET / Credential
```

Content 分类为：

```text
SENSITIVE
```

检测到 Password、Token、Private Key、API Key、Session Cookie 或 Authorization Header 时：

```text
400 VALIDATION_ERROR
```

Error 不回显 Secret。

---

# 8. Ticket State Guard

允许：

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

拒绝：

```text
CLOSED
CANCELLED
```

返回：

```text
409 MESSAGE_NOT_ALLOWED_IN_STATE
```

特殊规则：

- `WAITING_FOR_USER` 收到 Employee Message 时，本 Phase 只写 Message，不改变状态、不恢复 Workflow。
- `RESOLVED` 收到反馈时，本 Phase 不自动 Reopen。

---

# 9. Domain Model

推荐：

```java
TicketMessage.create(
    TicketMessageId messageId,
    TicketId ticketId,
    TicketMessageType messageType,
    MessageVisibility visibility,
    MessageAuthor author,
    MessageContent content,
    CommandId commandId,
    Instant createdAt
)
```

初始值：

```text
version = 0
createdAt = now
deletedAt = null
```

Domain Event：

```text
TicketMessageAdded
```

Event 只包含：

```text
messageId
ticketId
messageType
visibility
authorType
createdAt
```

不包含完整 Content。

---

# 10. Append-only

- 不提供 Update API。
- 不提供 Delete API。
- 数据库应用角色不执行普通 UPDATE / DELETE。
- 更正通过新 Message 完成。
- Phase 02 不实现软删除。
- Version 保持 0，除非未来 ADR 引入修订模型。

---

# 11. Idempotency

Scope：

```text
actor + ticketId + addTicketMessage + idempotencyKey
```

Hash 包含：

```text
method
normalized route
ticketId
actor scope
canonical actor-specific body
```

TTL：

```text
24 hours
```

Stale Threshold：

```text
5 minutes
```

行为：

```text
same key + same payload
→ original 201 response

same key + different payload
→ 409 IDEMPOTENCY_KEY_REUSED

fresh in-progress
→ 409 REQUEST_IN_PROGRESS

stale in-progress
→ reconcile; never create a second Message
```

Replay 不创建新的 Message、Audit 或 Outbox。

并发目标：

```text
100 identical requests
→ exactly one Message
```

---

# 12. Transaction Boundary

```text
BEGIN
1. Reserve Idempotency Record
2. Load minimal Ticket write guard
3. Verify resource authorization
4. Verify Ticket state
5. Create Message
6. Insert ticket.ticket_messages
7. Insert ticket.audit_records
8. Insert ticket.outbox_events
9. Complete Idempotency Record
10. COMMIT
```

任一步失败：

```text
ROLLBACK ALL
```

事务内禁止：

- RabbitMQ Publish
- Notification Call
- Agent Runtime Call
- Remote Policy Call
- External HTTP
- LangSmith Call
- Telemetry Export Wait

本 Spec 不更新：

```text
ticket.status
ticket.version
ticket.updatedAt
ticket.lastActivityAt
```

---

# 13. Persistence

新增：

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
source_command_id
trace_id
data_classification
created_at
version
```

约束：

- PK / FK
- Message Type Check
- Visibility Check
- Type/Visibility Combination Check
- Content Length Check
- CreatedAt Not Null
- Append-only Privilege

主要索引：

```text
(ticket_id, created_at ASC, message_id ASC)
```

---

# 14. Business Audit

成功写入：

```text
action = TICKET_MESSAGE_ADDED
```

Internal Note 可使用：

```text
action = TICKET_INTERNAL_NOTE_ADDED
```

记录：

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

Audit 不保存：

- Content
- Request Body
- JWT
- Idempotency-Key
- Secret

Required Audit 失败时 Fail Closed 并全部回滚。

---

# 15. Integration Event

```text
routingKey = ticket.message.added.v1
eventType = ticket.message.added
eventVersion = 1.0
aggregateType = TicketMessage
aggregateId = messageId
aggregateVersion = 0
partitionKey = ticketId
dataClassification = INTERNAL
```

Payload：

```json
{
  "messageId": "0190abcd-1234-7000-8000-000000000001",
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "messageType": "PUBLIC_REQUESTER_MESSAGE",
  "visibility": "PUBLIC",
  "authorType": "EMPLOYEE",
  "createdAt": "2026-07-25T18:30:00Z"
}
```

Event 禁止：

```text
content
title
description
raw authorId
email
JWT
Idempotency-Key
credential
```

同步成功只要求 Outbox Record Commit，不等待 Broker Confirm。

---

# 16. Response

```json
{
  "messageId": "0190abcd-1234-7000-8000-000000000001",
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "messageType": "PUBLIC_REQUESTER_MESSAGE",
  "visibility": "PUBLIC",
  "authorType": "EMPLOYEE",
  "content": "I restarted the VPN client, but the error still appears.",
  "createdAt": "2026-07-25T18:30:00Z",
  "version": 0
}
```

Replay 返回同一结果，并可增加：

```http
Idempotency-Replayed: true
```

---

# 17. Errors

| 场景 | HTTP | Code |
|---|---:|---|
| Invalid ID / Content / Secret | 400 | `VALIDATION_ERROR` |
| Missing Idempotency-Key | 400 | `VALIDATION_ERROR` |
| Invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing Scope | 403 | `FORBIDDEN` |
| Missing / Hidden Ticket | 404 | `TICKET_NOT_FOUND` |
| Closed / Cancelled | 409 | `MESSAGE_NOT_ALLOWED_IN_STATE` |
| Key reused with different payload | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Request still processing | 409 | `REQUEST_IN_PROGRESS` |
| Rate limited | 429 | `RATE_LIMITED` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Error 不暴露 Content、Secret、SQL、Table、Constraint、Stack Trace 或 JWT。

---

# 18. Observability

Trace：

```text
AddTicketMessageUseCase
ticket.message.authorization
ticket.message.state_guard
ticket.message.create
db.message.insert
db.audit.insert
db.outbox.insert
db.idempotency.complete
```

Metrics：

```text
opsmind_ticket_message_add_total
opsmind_ticket_message_add_duration_seconds
opsmind_ticket_message_replay_total
opsmind_ticket_message_state_rejected_total
opsmind_ticket_message_authorization_denied_total
opsmind_ticket_message_secret_rejected_total
```

允许低基数 Label：

```text
actor_type
message_type
visibility
result
status_class
```

禁止 TicketId、MessageId、AuthorId 和 IdempotencyKey 作为 Label。

---

# 19. Tests First

```text
TicketMessageTest
MessageContentTest
TicketMessageTypeVisibilityTest
TicketMessageAddedDomainEventTest
AddTicketMessageApplicationServiceTest
AddTicketMessageStateGuardTest
AddTicketMessageAuthorizationTest
AddTicketMessageIdempotencyReplayTest
AddEmployeeMessageControllerTest
AddSupportMessageControllerTest
AddTicketMessageValidationTest
AddTicketMessageMassAssignmentTest
AddRequesterMessageOwnershipIT
AddSupportMessageScopeIT
AddInternalNoteScopeIT
AddInternalNoteVisibilityTest
FlywayTicketMessageMigrationIT
AddTicketMessagePersistenceIT
AddTicketMessageAtomicityIT
AddTicketMessageDoesNotMutateTicketIT
AddTicketMessageIdempotencyIT
AddTicketMessageConcurrentIdempotencyIT
TicketMessageAddedEventContractTest
TicketMessageAddedEventRedactionTest
TicketMessageAuditRedactionTest
TicketMessageTelemetryRedactionTest
```

---

# 20. Package Mapping

```text
ticket.api.publicapi
├── PublicTicketMessageController
├── EmployeeAddTicketMessageRequest
└── AddTicketMessageResponse

ticket.api.support
├── SupportTicketMessageController
└── SupportAddTicketMessageRequest

ticket.application.port.in
└── AddTicketMessageUseCase

ticket.application.command
├── AddTicketMessageCommand
└── AddTicketMessageResult

ticket.application.service
└── AddTicketMessageApplicationService

ticket.application.port.out
├── TicketMessageRepository
├── TicketMessageWriteGuardPort
├── AuditRecordPort
├── OutboxEventRepository
└── IdempotencyRepository

ticket.domain.message
├── TicketMessage
├── TicketMessageId
├── TicketMessageType
├── MessageVisibility
├── MessageContent
├── MessageAuthor
└── TicketMessageAdded
```

---

# 21. Definition of Done

- [ ] Employee 只能向自己的 Ticket 添加 Public Message。
- [ ] Support Public / Internal Scope 正确。
- [ ] Author 和 Visibility 服务端派生。
- [ ] Mass Assignment 被阻止。
- [ ] Content Validation 和 Secret Rejection 通过。
- [ ] CLOSED / CANCELLED 拒绝。
- [ ] RESOLVED 不自动 Reopen。
- [ ] WAITING_FOR_USER 不在 Phase 02 自动转换。
- [ ] Message Append-only。
- [ ] Idempotency 和 100 并发测试通过。
- [ ] Message、Audit、Outbox、Idempotency 原子提交。
- [ ] Ticket Status、Version、UpdatedAt 不变。
- [ ] Event Contract 和 Redaction Test 通过。
- [ ] PostgreSQL、ArchUnit、`./mvnw clean verify` 和 CI 通过。
- [ ] Traceability 更新。

---

# 22. 实现后保证

```text
Employee 和 Support 可以在严格权限边界内追加消息；
Internal Note 不会泄漏；
重复请求只产生一条 Message；
每条 Message 都可审计、可发布、不可修改，
并且不会隐式改变 Ticket 生命周期。
```
