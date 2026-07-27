# SPEC-TW-004 — Add Ticket Message

> **Spec ID:** SPEC-TW-004  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Actors:** EMPLOYEE, IT_SUPPORT, IT_ADMIN, IT_MANAGER  
> **API:** `POST /api/v1/tickets/{ticketId}/messages`  
> **Dependencies:** SPEC-TW-001 and SPEC-TW-002  
> **Published Event:** `ticket.message.added.v1`

---

# 1. Purpose

Defines the complete behavior for an authorized actor appending a message to an existing Ticket.

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

The system guarantees:

- Employees add only public messages to owned Tickets.
- Support adds public messages or internal notes only within authorized scope.
- Author, author type, and visibility are server-derived.
- Messages cannot be updated or deleted.
- Retries and concurrent duplicates create only one Message.
- Message, Audit, Outbox, and Idempotency commit atomically.
- Phase 02 message creation never automatically changes Ticket state.
- Full content never enters events, Audit, logs, or traces.

---

# 2. Scope

Included:

- `PUBLIC_REQUESTER_MESSAGE`
- `PUBLIC_SUPPORT_MESSAGE`
- `INTERNAL_SUPPORT_NOTE`
- JWT and scopes
- Resource authorization
- Content validation
- Ticket state guard
- Append-only persistence
- Idempotency
- Business Audit
- Outbox event
- Error contract
- Observability
- Automated tests

Excluded:

- Message edit/delete
- Attachments
- Email ingestion
- Notifications
- Automatic state transitions
- Waiting-for-user resume
- Automatic reopen
- Timeline query
- Message search
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

Success:

```http
HTTP 201 Created
Location: /api/v1/tickets/{ticketId}/messages/{messageId}
ETag: "0"
```

---

# 4. Actor-specific Requests

## Employee

```json
{
  "content": "I restarted the VPN client, but the error still appears."
}
```

The server enforces:

```text
messageType = PUBLIC_REQUESTER_MESSAGE
visibility = PUBLIC
authorType = EMPLOYEE
authorId = principal.subject
```

The Employee schema uses `additionalProperties = false`. The client cannot submit message type, visibility, author, ID, version, or internal metadata.

## Support

Public message:

```json
{
  "content": "The account has been unlocked. Please try again.",
  "messageType": "PUBLIC_SUPPORT_MESSAGE"
}
```

Internal note:

```json
{
  "content": "Identity verification is still required.",
  "messageType": "INTERNAL_SUPPORT_NOTE"
}
```

The server maps public messages to `PUBLIC` and internal notes to `INTERNAL`. Support cannot submit raw visibility.

---

# 5. Authentication and Authorization

Employees require:

```text
tickets:message:self
ticket.requesterId = principal.subject
```

Support public messages require `tickets:message:public`.

Internal notes require `tickets:message:internal`.

Support also requires matching Queue, Application, or Team resource scope.

Missing coarse scope returns `403 FORBIDDEN`.

A missing or out-of-scope Ticket returns `404 TICKET_NOT_FOUND`.

---

# 6. Message Types and Visibility

```text
PUBLIC_REQUESTER_MESSAGE → PUBLIC
PUBLIC_SUPPORT_MESSAGE   → PUBLIC
INTERNAL_SUPPORT_NOTE    → INTERNAL
```

Clients cannot define arbitrary types or visibility.

Internal notes never appear in Employee APIs, Employee Timeline, or public notifications.

---

# 7. Content Validation

Rules:

```text
1–8000 characters after trim
UTF-8
not blank
no dangerous control characters
HTML is untrusted
scripts are never executed
secrets and credentials are prohibited
```

Content is `SENSITIVE`.

Passwords, tokens, private keys, API keys, session cookies, and authorization headers produce `400 VALIDATION_ERROR`. Errors never echo the secret.

---

# 8. Ticket State Guard

Allowed:

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

Rejected:

```text
CLOSED
CANCELLED
```

with `409 MESSAGE_NOT_ALLOWED_IN_STATE`.

An Employee reply in `WAITING_FOR_USER` creates only the Message. Workflow resume belongs to Phase 04.

Feedback in `RESOLVED` does not automatically reopen the Ticket.

---

# 9. Domain Model

Recommended:

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

Initial state:

```text
version = 0
createdAt = now
deletedAt = null
```

The Domain emits `TicketMessageAdded` with IDs, type, visibility, author type, and created time, but not full content.

---

# 10. Append-only

- No update API.
- No delete API.
- The database application role has no normal UPDATE or DELETE permission.
- Corrections use a new Message.
- No soft delete in Phase 02.
- Version remains 0 unless a later ADR introduces revisions.

---

# 11. Idempotency

Scope:

```text
actor + ticketId + addTicketMessage + idempotencyKey
```

The request hash contains method, normalized route, Ticket ID, actor scope, and canonical actor-specific body.

TTL is 24 hours; stale threshold is 5 minutes.

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

Replay creates no new Message, Audit, or Outbox record.

Concurrency target:

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

Any failure rolls back everything.

No broker publish, notification, Agent Runtime, remote policy, external HTTP, LangSmith, or telemetry-export wait occurs inside the transaction.

This Spec does not update Ticket status, version, updatedAt, or lastActivityAt.

---

# 13. Persistence

Add:

```text
ticket.ticket_messages
```

Recommended fields:

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

Enforce PK/FK, type, visibility, combination, length, timestamp, and append-only constraints.

Primary query index:

```text
(ticket_id, created_at ASC, message_id ASC)
```

---

# 14. Business Audit

Use `TICKET_MESSAGE_ADDED`, or `TICKET_INTERNAL_NOTE_ADDED` for internal notes.

Record actor, Ticket ID, Message ID, type, visibility, trace, command, outcome, and time.

Audit excludes content, request body, JWT, Idempotency Key, and secrets.

Required Audit failure causes fail-closed rollback.

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

The payload contains Message ID, Ticket ID, type, visibility, author type, and created time.

It excludes content, title, description, raw author ID, email, JWT, Idempotency Key, and credentials.

API success requires Outbox commit, not broker confirmation.

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

Replay returns the same response and may add `Idempotency-Replayed: true`.

---

# 17. Errors

| Scenario | HTTP | Code |
|---|---:|---|
| Invalid ID, content, or secret | 400 | `VALIDATION_ERROR` |
| Missing Idempotency Key | 400 | `VALIDATION_ERROR` |
| Invalid JWT | 401 | `UNAUTHENTICATED` |
| Missing scope | 403 | `FORBIDDEN` |
| Missing or hidden Ticket | 404 | `TICKET_NOT_FOUND` |
| Closed or Cancelled | 409 | `MESSAGE_NOT_ALLOWED_IN_STATE` |
| Key reused with different payload | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Request still processing | 409 | `REQUEST_IN_PROGRESS` |
| Rate limited | 429 | `RATE_LIMITED` |
| PostgreSQL unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Errors never expose content, secrets, SQL, tables, constraints, stack traces, or JWTs.

---

# 18. Observability

Spans:

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

Metrics:

```text
opsmind_ticket_message_add_total
opsmind_ticket_message_add_duration_seconds
opsmind_ticket_message_replay_total
opsmind_ticket_message_state_rejected_total
opsmind_ticket_message_authorization_denied_total
opsmind_ticket_message_secret_rejected_total
```

Allowed low-cardinality labels: actor type, message type, visibility, result, and status class.

Never label with Ticket ID, Message ID, Author ID, or Idempotency Key.

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

- [ ] Employees add only public messages to owned Tickets.
- [ ] Support public and internal scopes are correct.
- [ ] Author and visibility are server-derived.
- [ ] Mass assignment is blocked.
- [ ] Content validation and secret rejection pass.
- [ ] Closed and Cancelled reject writes.
- [ ] Resolved does not auto-reopen.
- [ ] Waiting for User does not auto-transition in Phase 02.
- [ ] Messages are append-only.
- [ ] Idempotency and 100-concurrent-request tests pass.
- [ ] Message, Audit, Outbox, and Idempotency commit atomically.
- [ ] Ticket status, version, and updatedAt remain unchanged.
- [ ] Event contract and redaction tests pass.
- [ ] PostgreSQL, ArchUnit, `./mvnw clean verify`, and CI pass.
- [ ] Traceability is updated.

---

# 22. Business Guarantee

```text
Employees and Support append messages within strict authorization boundaries.
Internal notes do not leak.
Retries create only one Message.
Every Message is auditable, publishable, immutable,
and never implicitly changes the Ticket lifecycle.
```
