# OpsMind Ticket Workflow — Phase 02 Ticket Query and Message Slice

> **Document ID:** IMP-TW-P02  
> **Domain:** `02-ticket-workflow`  
> **Phase:** Phase 02  
> **Phase Name:** Ticket Query and Message Slice  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Prerequisite:** Phase 01 Create Ticket Vertical Slice exit criteria passed  
> **Primary Feature Specs:**
>
> - `SPEC-TW-002-get-ticket`
> - `SPEC-TW-003-list-requester-tickets`
> - `SPEC-TW-004-add-ticket-message`
> - `SPEC-TW-005-support-queue-query`
> - `SPEC-TW-006-ticket-timeline`
>
> **Code Directory:** `services/ticket-workflow-service/`  
> **Spec Directory:** `docs/specs/domains/02-ticket-workflow/`  
> **Traceability:** `docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml`

---

# 1. Objective

Phase 02 builds on Phase 01 Ticket creation and delivers the core Ticket query and communication capabilities.

At the end of this phase:

```text
Employee
→ views one owned Ticket
→ lists owned Tickets
→ adds a public message
→ views a public Timeline

IT Support
→ views an authorized Support Queue
→ views an authorized Ticket
→ adds a public support message or internal note
→ views an authorized support Timeline
```

This phase establishes:

- Ticket read models
- Resource-ownership authorization
- Support-queue authorization
- Cursor pagination
- Public and internal message visibility
- Append-only messages
- Timeline projections
- Query-side performance baselines
- Query and message audit
- Query and message observability

---

# 2. Why Phase 02 Follows Phase 01

Phase 01 only proves:

```text
An Employee can reliably create a Ticket
```

The system still cannot:

- View the newly created Ticket.
- List Tickets.
- Add supporting information.
- Let Support view a working queue.
- Display a complete activity timeline.

Phase 03 Triage and Agent Workflow need stable query and message behavior because:

- Agents need Ticket context.
- Employees need to provide additional details.
- Support needs a queue.
- Future state, approval, tool, and verification records need a timeline projection.

Phase 02 is therefore the required bridge between Ticket creation and automated workflow orchestration.

---

# 3. Preconditions

Before Phase 02 begins:

- Phase 00 is complete.
- Phase 01 passes Exit Review.
- `SPEC-TW-001-create-ticket` is complete.
- `POST /api/v1/tickets` works.
- Ticket, Resolution Cycle, SLA Cycle, History, Audit, Outbox, and Idempotency commit atomically.
- Ticket identity and display ID are frozen.
- Security-principal mapping is available.
- The error envelope is available.
- PostgreSQL Testcontainers work.
- ArchUnit and CI work.
- `./mvnw clean verify` passes.

---

# 4. Feature Spec Breakdown

Phase 02 contains five Feature Specs:

```text
SPEC-TW-002 Get Ticket
SPEC-TW-003 List Requester Tickets
SPEC-TW-004 Add Ticket Message
SPEC-TW-005 Support Queue Query
SPEC-TW-006 Ticket Timeline
```

Recommended order:

```text
SPEC-TW-002
→ SPEC-TW-003
→ SPEC-TW-004
→ SPEC-TW-005
→ SPEC-TW-006
```

Rationale:

1. Establish a single-Ticket read model.
2. Establish requester list queries.
3. Add message commands.
4. Add support-queue queries.
5. Combine Ticket, History, Message, and safe business-action projections into the Timeline.

Each Spec independently follows:

```text
Spec Review
→ RED
→ GREEN
→ REFACTOR
→ VERIFY
→ Traceability Update
```

---

# 5. Design References

Phase 02 primarily references:

## `01-domain-model`

For:

- Ticket identity
- Requester ownership
- Message entity
- Message visibility
- Assignment and queue fields
- Ticket summaries
- Timeline item identity

## `02-business-invariants`

At minimum:

- Resource ownership
- Append-only messages
- Internal-message visibility
- Terminal-state message rules
- Query field visibility
- Pagination consistency
- Timeline ordering
- Audit requirements

Each Feature Spec must list exact invariant IDs.

## `03-state-machine`

Phase 02 introduces no main lifecycle transition, but must enforce:

- Queries never mutate state.
- Add Message never uses a generic status mutation.
- Terminal-state message rules are explicit.
- Automatic resume from `WAITING_FOR_USER` belongs to Phase 04.

## `04-use-cases`

Map the frozen use-case IDs for:

```text
Get Ticket
List Requester Tickets
Add Message
Support Queue Query
Get Timeline
```

## `05-api-contracts`

Implements:

```text
GET /api/v1/tickets/{ticketId}
GET /api/v1/tickets
POST /api/v1/tickets/{ticketId}/messages
GET /api/v1/support/tickets
GET /api/v1/tickets/{ticketId}/timeline
```

Final paths follow the approved API contract.

## `06-event-contracts`

Phase 02 may publish:

```text
ticket.message.added.v1
```

when the event is frozen in the LLD.

Pure queries do not emit business events.

## `07-data-model`

Adds or expands:

```text
ticket.ticket_messages
query indexes
support-queue indexes
timeline-query indexes
```

Reuses:

```text
ticket.tickets
ticket.ticket_status_history
ticket.audit_records
ticket.outbox_events
```

## `08-transaction-and-outbox`

Add Message transaction:

```text
Message
+ Required Audit
+ Optional Outbox Event
```

Queries do not start unnecessary write transactions.

## `09-concurrency-and-idempotency`

Add Message requires:

- `Idempotency-Key`
- Same key and same payload replay
- Same key and different payload conflict
- Concurrent duplicate prevention

GET queries are naturally idempotent and do not require an Idempotency Key.

## `10-error-handling-and-reconciliation`

Implements:

- Ticket not found
- Resource hidden as not found
- Forbidden queue access
- Invalid cursor
- Invalid message
- Message conflict
- Terminal-state write rejection
- Safe query failures

## `11-security-and-authorization`

Implements:

- Requester ownership
- Support-queue authorization
- Public and internal field visibility
- Internal-message permission
- Sensitive-read audit
- No cross-user reads

## `12-observability-and-audit`

Implements:

- Read counters
- Query duration
- Queue-query duration
- Message-add counter
- Authorization-denied counter
- Sensitive-read audit
- Internal-message audit
- No high-cardinality metric labels

## `13-package-and-class-design`

Implements:

- Query API adapters
- Query application services
- Query ports
- JDBC projection adapters
- Message command service
- Message domain model
- Message persistence adapter
- Timeline projection service

## `14-testing-strategy`

Implements:

- Query unit and slice tests
- Security tests
- PostgreSQL integration tests
- Pagination tests
- Visibility tests
- Message atomicity tests
- Timeline ordering tests
- Contract tests
- Performance baseline tests

---

# 6. Scope

Phase 02 includes:

- Get Ticket by ID
- List Requester Tickets
- Add public requester message
- Add public support message
- Add internal support note
- Support Queue query
- Ticket Timeline query
- Public and internal field visibility
- Cursor pagination
- Stable sorting
- Append-only messages
- Message idempotency
- Query audit where required
- Message business audit
- Message Outbox event where required
- Query and message telemetry
- Query and message tests

---

# 7. Non-goals

Phase 02 does not implement:

- Agent triage
- Ticket classification
- Waiting-for-user workflow
- Approval
- Tool execution
- Verification
- Resolution
- Close, reopen, or cancel
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
- Generic analytics dashboards
- Cross-domain memory retrieval

Phase 02 may display assignment and queue fields but does not implement complete assignment commands.

---

# 8. Query Architecture

Phase 02 uses lightweight CQRS:

```text
Command Side
→ Domain Aggregate
→ JPA / Persistence Adapter

Query Side
→ Query Service
→ JDBC Projection
→ DTO
```

Principles:

- Queries do not rehydrate the full Ticket aggregate.
- Queries do not assemble large lazy JPA graphs.
- Queries use explicit SQL or JDBC projections.
- Query DTOs are separate from domain objects.
- Actors receive only authorized fields.
- Authorization filters are pushed into SQL where possible.
- Sensitive records are not loaded and filtered later in memory.

---

# 9. SPEC-TW-002 — Get Ticket

## Objective

Allow an authorized actor to view the current detail of one Ticket.

## Actors

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR with approved scope
```

## Authorization

Employee:

```text
ticket.requesterId == principal.subject
```

Support:

```text
principal has access to the Ticket queue, application, or assignment scope
```

An Employee attempting to access another user's Ticket should normally receive:

```text
404 TICKET_NOT_FOUND
```

to reduce resource enumeration.

Support access outside its authorized scope returns `403` or a hidden `404` according to the frozen Security Contract.

## Response Views

Employee view may include:

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
- Public latest-message summary
- Allowed SLA summary

Employee view excludes:

- Internal messages
- Internal notes
- Risk score
- Security flags
- Approval internals
- Tool credentials
- Internal actor identifiers
- Reconciliation metadata

Support views may include more fields under explicit scope control.

## Consistency

Default:

```text
Read Committed
```

The response returns:

```text
ETag = current version
```

Get Ticket never modifies `updatedAt`.

---

# 10. SPEC-TW-003 — List Requester Tickets

## Objective

Allow an Employee to list Tickets they created.

## Endpoint

Recommended:

```text
GET /api/v1/tickets
```

## Filters

MVP allows a bounded filter set:

```text
status
applicationCode
createdFrom
createdTo
```

No arbitrary query language.

## Sorting

Default:

```text
createdAt DESC, ticketId DESC
```

Allowed sorts are whitelisted.

## Cursor Pagination

Use an:

```text
opaque cursor
```

The cursor encodes at least:

```text
lastCreatedAt
lastTicketId
filter fingerprint
sort version
```

Rules:

- No raw SQL exposure.
- Cursor is signed or tamper-resistant.
- Cursor and current filters must match.
- Offset is not the primary pagination mechanism.
- Page size has a hard maximum, such as 50.
- Default page size may be 20.

## Ownership

SQL contains:

```text
requester_id = principal.subject
```

Memory filtering is not sufficient.

---

# 11. SPEC-TW-004 — Add Ticket Message

## Objective

Allow Employees and IT Support to append messages to a Ticket.

## Message Types

```text
PUBLIC_REQUESTER_MESSAGE
PUBLIC_SUPPORT_MESSAGE
INTERNAL_SUPPORT_NOTE
```

Employees create only:

```text
PUBLIC_REQUESTER_MESSAGE
```

IT Support may create:

```text
PUBLIC_SUPPORT_MESSAGE
INTERNAL_SUPPORT_NOTE
```

## Visibility

```text
PUBLIC
INTERNAL
```

Employees cannot:

- Select `INTERNAL`
- Create internal notes
- View internal notes
- Inject visibility through unknown fields

## Append-only

After creation:

- No update
- No delete
- No overwrite
- Corrections use a new message
- The application role has no normal UPDATE or DELETE privilege

## Request

Example:

```json
{
  "content": "I restarted the VPN client, but the error still appears.",
  "messageType": "PUBLIC_REQUESTER_MESSAGE"
}
```

Clients do not provide:

```text
messageId
authorId
authorType
visibility
createdAt
ticketVersion
internalMetadata
```

For Employee APIs, the endpoint may infer message type to further reduce mass-assignment risk.

## Validation

- Content is required.
- Maximum length is frozen in the Spec.
- Secrets are prohibited.
- Content is safely rendered.
- Whitespace-only content is rejected.
- Raw HTML is not trusted.
- Attachments are outside this phase.

## State Rules

Phase 02 appends messages without automatically changing Ticket state.

Specifically:

```text
WAITING_FOR_USER + requester reply
```

workflow resume belongs to Phase 04.

Terminal-state defaults:

- `CLOSED`: reject normal messages; reopen first.
- `CANCELLED`: reject messages.
- `FAILED` and `ESCALATED`: follow frozen LLD rules.
- `RESOLVED`: requester feedback may be allowed, but Phase 02 does not auto-reopen.

## Idempotency

Require:

```text
Idempotency-Key
```

Same actor, Ticket, key, and payload:

```text
return original Message
```

Different payload:

```text
409 IDEMPOTENCY_KEY_REUSED
```

## Transaction

One transaction:

```text
Insert Message
Insert Business Audit
Insert ticket.message.added Outbox Event when required
Complete Idempotency Record
```

Message creation does not change aggregate version without an explicit design reason.

When `lastActivityAt` or `updatedAt` must change, the Spec defines the safe update and concurrency rule.

---

# 12. SPEC-TW-005 — Support Queue Query

## Objective

Allow IT Support to view Tickets within authorized support scope.

## Authorization Dimensions

May include:

```text
applicationCode
supportTeamId
assignmentGroup
region
tenant
sensitivity
role
```

Final dimensions follow the Security LLD.

## Default Queue

Recommended:

```text
non-terminal Tickets
within authorized support scope
ordered by priority and SLA urgency
```

Recommended stable ordering:

```text
SLA breach state
priority
createdAt
ticketId
```

## Filters

MVP may support:

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

Filters are:

- Whitelisted
- Parameterized
- Index-supported
- Restricted to approved sort fields

## Field Visibility

Queue summaries exclude:

- Full description
- All messages
- Secrets
- Credentials
- Unnecessary raw requester attributes

## Sensitive Read Audit

Opening sensitive Ticket detail may require audit.

A queue query should not automatically create one expensive Audit record per returned row unless policy explicitly requires it.

---

# 13. SPEC-TW-006 — Ticket Timeline

## Objective

Provide a chronological Ticket activity view.

Timeline sources may include:

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

Phase 02 initially has:

```text
Initial Status History
Public Messages
Internal Notes
Ticket Created summary
```

Future phases extend the same projection.

## Views

Employee Timeline:

```text
PUBLIC items only
```

Support Timeline:

```text
PUBLIC + authorized INTERNAL items
```

Auditor Timeline:

```text
policy-approved audit view
```

## Ordering

Stable ordering:

```text
occurredAt ASC
itemTypeOrder ASC
itemId ASC
```

or a frozen reverse order.

## Pagination

Timeline uses cursor pagination.

The cursor contains:

```text
occurredAt
itemTypeOrder
itemId
view type
```

## Projection

Every item normalizes to:

```text
itemId
itemType
visibility
occurredAt
actor summary
safe summary
related version
```

Employee views never expose raw audit metadata.

---

# 14. Data Model Changes

Phase 02 adds at least:

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
created_at
source_command_id
source_event_id
trace_id
data_classification
```

Constraints:

- Primary key on `message_id`
- Foreign key to `ticket_id`
- Message-type check
- Visibility check
- Content-length check
- Required CreatedAt
- Append-only privilege
- Optional unique command correlation

Message idempotency may reuse:

```text
ticket.idempotency_records
```

---

# 15. Index Strategy

Evaluate at least:

## Requester List

```text
(requester_id, created_at DESC, ticket_id DESC)
```

## Ticket Messages

```text
(ticket_id, created_at ASC, message_id ASC)
```

## Support Queue

Potential partial or composite indexes for:

```text
(status, application_code, priority, created_at, ticket_id)
assigned_team_id
assigned_agent_id
sla_state
```

Do not add speculative indexes without query-plan evidence.

Indexes require support from:

- Query plans
- Integration tests
- Explain Analyze
- Actual query patterns

---

# 16. Security and Field Visibility

## Employee

May:

- View owned Tickets
- List owned Tickets
- Add public requester messages
- View public Timeline items

May not:

- View another user's Ticket
- View internal notes
- View the Support Queue
- Forge the author
- Select internal visibility

## IT Support

Requires approved scopes such as:

```text
tickets:read:queue
tickets:message:public
tickets:message:internal
```

and matching resource scope.

## Admin, Manager, and Auditor

Receive views according to approved scope and field policy.

## Resource Hiding

Cross-user Employee access returns:

```text
404 TICKET_NOT_FOUND
```

to reduce ID enumeration.

---

# 17. Audit Requirements

At minimum audit:

```text
TICKET_VIEWED_SENSITIVE
TICKET_MESSAGE_ADDED
TICKET_INTERNAL_NOTE_ADDED
SUPPORT_QUEUE_ACCESSED when policy requires
TICKET_TIMELINE_VIEWED when sensitive
```

Audit never stores complete message content.

Message audit includes:

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

Query audit is cost-aware; normal requester-list rows do not each create an Audit record.

---

# 18. Observability

## Metrics

Recommended:

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

Never use:

```text
ticketId
messageId
requesterId
cursor
idempotencyKey
```

## Traces

At minimum:

```text
GetTicketUseCase
ListRequesterTicketsUseCase
AddTicketMessageUseCase
QuerySupportQueueUseCase
GetTicketTimelineUseCase
```

## Logging

Do not log:

- Message content
- Full description
- Raw cursor payload
- JWT
- Idempotency Key

---

# 19. TDD Execution Order

## SPEC-TW-002

```text
Spec Review
→ GetTicketSecurityTest RED
→ GetTicketQueryTest RED
→ JDBC Projection GREEN
→ Field Visibility Test
→ Verify
```

## SPEC-TW-003

```text
Spec Review
→ Ownership Query RED
→ Cursor Pagination RED
→ Stable Sorting GREEN
→ Invalid Cursor Test
→ Verify
```

## SPEC-TW-004

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

## SPEC-TW-005

```text
Spec Review
→ Queue Authorization RED
→ Filter and Sort RED
→ Projection GREEN
→ Query Plan Review
→ Verify
```

## SPEC-TW-006

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

# 21. Recommended Package Mapping

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

## P02-T01 Review Phase Scope

Confirm five Specs and implementation order.

## P02-T02 Write SPEC-TW-002

Get Ticket.

## P02-T03 Implement Get Ticket

Requester and Support views.

## P02-T04 Write SPEC-TW-003

List Requester Tickets.

## P02-T05 Implement Requester List

Cursor, filters, and stable sorting.

## P02-T06 Write SPEC-TW-004

Add Ticket Message.

## P02-T07 Add Message Migration

Create only message tables and indexes.

## P02-T08 Implement Message Domain and Command

Public and internal visibility, append-only behavior, and idempotency.

## P02-T09 Add Message Event and Audit

Outbox, redaction, and audit.

## P02-T10 Write SPEC-TW-005

Support Queue Query.

## P02-T11 Implement Support Queue

Authorization, filters, projections, and indexes.

## P02-T12 Write SPEC-TW-006

Ticket Timeline.

## P02-T13 Implement Timeline Projection

Visibility, ordering, and cursor.

## P02-T14 Add Telemetry

Query and message metrics, traces, and logs.

## P02-T15 Update Traceability

Map five Specs to code and tests.

## P02-T16 Update README

Curl, authorization, pagination, visibility, and non-goals.

---

# 23. Recommended Pull Requests

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
test(message): add domain, security, and atomicity tests
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

## Documentation

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

## Code

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
Message Outbox
Telemetry
```

## Database

```text
ticket.ticket_messages
required query indexes
```

## Tests

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

# 25. Risks and Mitigations

## Risk 1 — Queries Rehydrate the Full Aggregate

Mitigation:

- Query side uses JDBC projections.
- Only command side uses aggregates.

## Risk 2 — Loading All Data Before Authorization Filtering

Mitigation:

- Push ownership and queue scope into SQL.

## Risk 3 — Internal Notes Leak to Employees

Mitigation:

- Separate public and support DTOs.
- Include visibility in queries.
- Add security and redaction tests.

## Risk 4 — Unstable Cursor Pagination

Mitigation:

- Use a unique tie-breaker.
- Bind cursor to filters and sort version.
- Add concurrent-insert pagination tests.

## Risk 5 — Message Creation Changes Ticket State Implicitly

Mitigation:

- Phase 02 never auto-transitions state.
- Waiting-for-user resume belongs to Phase 04.

## Risk 6 — Messages Can Be Edited or Deleted

Mitigation:

- Append-only privileges.
- No update or delete APIs.
- Corrections use new messages.

## Risk 7 — Slow Support Queue

Mitigation:

- Bounded filters.
- Explicit projections.
- Explain Analyze.
- Evidence-based indexes.

## Risk 8 — Excessive Query Audit Volume

Mitigation:

- Detailed audit for sensitive-detail reads.
- Aggregate or policy-driven low-cost audit for lists and queues.

---

# 26. Exit Criteria

Phase 02 is complete only when all conditions pass.

## Specs

- All five Feature Specs are reviewed.
- Scope and non-goals are frozen.
- API, security, pagination, and visibility rules are complete.

## Get Ticket

- Employees can read only owned Tickets.
- Support can read only authorized queue Tickets.
- Employee views hide internal fields.
- ETag matches Ticket version.
- Cross-user access does not reveal resource existence.

## Requester List

- SQL enforces requester ownership.
- Cursor pagination is stable.
- Cursor and filter mismatch is rejected.
- Page size has a hard maximum.
- No unbounded list query exists.

## Messages

- Employees create only public requester messages.
- Internal notes require explicit Support scope.
- Messages are append-only.
- Content validation passes.
- Message idempotency passes.
- Concurrent duplicates do not create duplicate messages.
- Message, Audit, Outbox, and Idempotency commit atomically.
- Terminal-state message rules pass.

## Support Queue

- Queue authorization is pushed into the query.
- Filters are whitelisted.
- Sorting is stable.
- Sensitive fields are minimized.
- Query plans meet the baseline.

## Timeline

- Employee Timeline shows only public items.
- Support Timeline shows authorized internal items.
- Ordering is stable.
- Cursor behavior is stable.
- Future timeline item types are extensible.

## Security

- Resource-ownership tests pass.
- Internal-visibility tests pass.
- Support-queue authorization tests pass.
- Sensitive-read audit follows policy.
- No mass assignment exists.

## Observability

- Query and message metrics are correct.
- No high-cardinality labels.
- Logs exclude message content, description, JWT, and cursor payload.
- Traces correlate API and database queries.

## Quality

```text
./mvnw clean verify
```

passes.

- PostgreSQL integration tests pass.
- ArchUnit passes.
- Secret scanning passes.
- CI passes.
- Docker image starts.
- Traceability is updated.
- README is updated.

---

# 27. Exit Review Checklist

- [ ] Phase 01 is complete.
- [ ] SPEC-TW-002 is complete.
- [ ] SPEC-TW-003 is complete.
- [ ] SPEC-TW-004 is complete.
- [ ] SPEC-TW-005 is complete.
- [ ] SPEC-TW-006 is complete.
- [ ] Employee ownership is enforced.
- [ ] Support queue scope is enforced.
- [ ] Public and internal visibility are separated.
- [ ] Cursor pagination is stable.
- [ ] Messages are append-only.
- [ ] Message idempotency passes.
- [ ] Message atomicity passes.
- [ ] Message event contract passes.
- [ ] Timeline ordering passes.
- [ ] Query plans are reviewed.
- [ ] Sensitive-read audit is verified.
- [ ] Telemetry redaction tests pass.
- [ ] ArchUnit passes.
- [ ] `./mvnw clean verify` passes.
- [ ] CI passes.
- [ ] Traceability is updated.
- [ ] README is updated.

---

# 28. What Phase 02 Enables

After Exit Review, the roadmap advances to:

```text
Phase 03 — Triage and Investigation Slice
```

Phase 03 can safely depend on:

- Ticket queries
- Ticket context
- Public messages
- Internal notes
- Support Queue
- Timeline
- Resource authorization
- Query projections
- Existing audit and trace context

Next Feature Specs:

```text
SPEC-TW-007-start-triage
SPEC-TW-008-complete-classification
SPEC-TW-009-agent-workflow-failure
```

---

# 29. Definition of Done

Phase 02 is complete when:

```text
OpsMind has evolved from a system that can only create Tickets
into a usable support system where Employees and IT Support can
securely read, list, communicate, and view Ticket activity,
with query, visibility, idempotency, audit, and performance
behavior protected by Feature Specs and automated tests.
```
