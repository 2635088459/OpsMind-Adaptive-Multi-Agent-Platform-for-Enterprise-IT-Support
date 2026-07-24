# OpsMind Ticket Workflow — 04 Use Cases

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Use Case Specification  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependencies:** `01-domain-model/README_EN.md`, `02-business-invariants/README_EN.md`, `03-state-machine/README_EN.md`  
> **Recommended Path:** `docs/low-level-design/domains/02-ticket-workflow/04-use-cases/README_EN.md`

---

## 1. Purpose

This document defines the primary Ticket Workflow MVP use cases.

Each use case specifies:

- Actor
- Trigger
- Command or Query
- Preconditions
- Main flow
- Alternative flow
- Failure flow
- State transition
- Applicable business invariants
- Transaction boundary
- Domain event
- Integration event
- Idempotency
- Authorization
- Observability
- Expected result

It is a direct input to API, event, data, transaction, concurrency, class, and testing design.

---

# 2. Use Case Index

```text
UC-01 Create Ticket
UC-02 Get Ticket
UC-03 List Requester Tickets
UC-04 List Support Queue Tickets
UC-05 Add Ticket Message
UC-06 Start Triage
UC-07 Complete Classification
UC-08 Request More Information
UC-09 Receive User Reply
UC-10 Associate Active Workflow
UC-11 Request Approval
UC-12 Handle Approval Granted
UC-13 Handle Approval Rejected
UC-14 Handle Approval Expired
UC-15 Start Auto-approved Tool Execution
UC-16 Handle Tool Execution Success
UC-17 Handle Tool Execution Failure
UC-18 Handle Unknown Tool Result
UC-19 Start Verification
UC-20 Handle Verification Success
UC-21 Handle Verification Failure
UC-22 Resolve Ticket
UC-23 Confirm and Close Ticket
UC-24 Auto-close Ticket
UC-25 Reopen Ticket
UC-26 Cancel Ticket
UC-27 Escalate Ticket
UC-28 Assign Ticket
UC-29 Retry Failed Automation
UC-30 Retrieve Ticket Timeline
```

---

# 3. Common Execution Template

Every command use case follows:

```text
1. Authenticate actor or validate service identity
2. Authorize operation
3. Validate request schema
4. Validate idempotency key or event ID
5. Load required aggregate
6. Validate expected version
7. Apply domain behavior
8. Persist aggregate changes
9. Insert history or append-only records
10. Insert outbox event
11. Mark inbound event processed
12. Commit
13. Return response
14. Publish asynchronously through Outbox
```

Query use cases do not mutate business state.

---

# 4. UC-01 Create Ticket

## Actor

```text
EMPLOYEE
IT_SUPPORT
AUTHORIZED_SERVICE
```

## Command

```text
CreateTicketCommand
├── requesterId
├── title
├── description
├── applicationCode
├── source
└── idempotencyKey
```

## Preconditions

- Authenticated actor
- Valid requester
- Valid title, description, and application
- Idempotency-Key is present

## Main Flow

1. Read or create idempotency record.
2. Generate TicketId and TicketDisplayId.
3. Call `Ticket.create(...)`.
4. Create initial TicketSla.
5. Save Ticket and SLA.
6. Insert initial history.
7. Insert `ticket.created.v1` Outbox Event.
8. Store idempotency result.
9. Commit.
10. Return Ticket snapshot.

## Alternative

Same requester, key, and payload returns the original Ticket.

## Failure

Same key with a different payload returns:

```text
IDEMPOTENCY_KEY_REUSED
```

## Transition

```text
SM-001 Initial → NEW
```

## Invariants

```text
BI-001–BI-008
BI-085
BI-088
BI-095
```

---

# 5. UC-02 Get Ticket

## Actors

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
```

## Query

```text
GetTicketQuery(ticketId, actorContext)
```

## Flow

1. Verify Ticket exists.
2. Calculate actor visibility.
3. Read Ticket detail model.
4. Redact fields.
5. Return result.

## Authorization

- Employee: own Ticket only
- Support: authorized queue
- Auditor: read-only
- Admin and Manager: role scope

Failures:

```text
TICKET_NOT_FOUND
FORBIDDEN
```

---

# 6. UC-03 List Requester Tickets

The requester ID comes from the security context, not an arbitrary client field.

Supports status filter, pagination, and sorting.

---

# 7. UC-04 List Support Queue Tickets

Validates queue membership before returning the support queue read model.

Failure:

```text
FORBIDDEN_QUEUE_ACCESS
```

---

# 8. UC-05 Add Ticket Message

## Command

```text
AddTicketMessageCommand
├── ticketId
├── author
├── type
├── visibility
├── body
├── attachmentIds
├── replyToMessageId?
└── idempotencyKey
```

## Flow

1. Load Ticket.
2. Create TicketMessage.
3. Validate visibility.
4. Save Message.
5. If it is a valid reply to WAITING_FOR_USER, execute UC-09.
6. Insert message event.
7. Commit.

## Invariants

```text
BI-024–BI-027
BI-095
```

Normal support messages do not automatically change Ticket state.

---

# 9. UC-06 Start Triage

## Trigger

```text
agent.workflow.started
or StartTriageCommand
```

## Preconditions

- Ticket is NEW
- No active workflow
- Workflow belongs to Ticket

## Flow

1. Deduplicate.
2. Load Ticket.
3. Call `startTriaging(...)`.
4. Associate active workflow.
5. Insert history and outbox.
6. Commit.

## Transition

```text
SM-002
```

## Invariants

```text
BI-017–BI-021
BI-089–BI-094
```

---

# 10. UC-07 Complete Classification

## Trigger

```text
ticket.classification.completed
```

## Preconditions

- Ticket is TRIAGING
- Workflow matches
- Category and subcategory are valid
- Confidence passes threshold or has human override

## Flow

1. Deduplicate event.
2. Validate workflow and version.
3. Update category, subcategory, and priority.
4. Complete classification.
5. Write category history.
6. Write status history and outbox.
7. Mark event processed.
8. Commit.

## Transition

```text
SM-003
```

## Events

```text
ticket.classified.v1
ticket.investigation_ready.v1
```

---

# 11. UC-08 Request More Information

## Actors

```text
AGENT_RUNTIME_SERVICE
IT_SUPPORT
```

## Preconditions

- Ticket is TRIAGING, INVESTIGATING, or ESCALATED
- Unique request ID
- Valid resume state
- No conflicting pending action

## Flow

1. Load Ticket.
2. Create request reference.
3. Request user input.
4. Create requester-visible message.
5. Pause SLA.
6. Write history and outbox.
7. Commit.

## Transitions

```text
SM-004
SM-007
SM-031
```

---

# 12. UC-09 Receive User Reply

## Preconditions

- Ticket is WAITING_FOR_USER
- RequestId matches
- Actor is requester or authorized support
- Resume state exists

## Flow

1. Validate idempotency.
2. Load Ticket.
3. Create user message.
4. Resume Ticket.
5. Clear open request.
6. Resume SLA.
7. Write history and outbox.
8. Commit.

## Transitions

```text
SM-005
SM-006
```

---

# 13. UC-10 Associate Active Workflow

Used only during creation, reopen, or valid recovery.

Rejects conflicting active workflows or mismatched references.

---

# 14. UC-11 Request Approval

## Trigger

```text
approval.requested
```

## Preconditions

- Ticket is INVESTIGATING
- No pending action
- Action belongs to active workflow
- Approval reference exists
- No credential data

## Flow

1. Deduplicate.
2. Load Ticket.
3. Create PendingActionReference.
4. Wait for approval.
5. Write history and outbox.
6. Mark event processed.
7. Commit.

## Transition

```text
SM-008
```

---

# 15. UC-12 Handle Approval Granted

## Trigger

```text
approval.granted
```

## Preconditions

- Ticket is WAITING_FOR_APPROVAL
- Ticket, workflow, action, type, and approval match
- Approval is not expired
- ToolExecutionId exists

## Flow

1. Deduplicate event.
2. Validate all references.
3. Authorize execution.
4. Write history and `ticket.execution_ready.v1`.
5. Mark processed.
6. Commit.

## Transition

```text
SM-011
```

Duplicate events are idempotent.

---

# 16. UC-13 Handle Approval Rejected

Invalidates the pending action, returns to INVESTIGATING, and publishes an investigation-resume event.

Transition:

```text
SM-012
```

---

# 17. UC-14 Handle Approval Expired

Same as UC-13. The expired approval ID cannot be reused.

Transition:

```text
SM-013
```

---

# 18. UC-15 Start Auto-approved Tool Execution

Used only when Policy explicitly returns `AUTO_APPROVED`.

The Ticket moves from INVESTIGATING to EXECUTING after storing the action and ToolExecutionId.

Transition:

```text
SM-009
```

---

# 19. UC-16 Handle Tool Execution Success

## Trigger

```text
tool.execution.completed
result = SUCCESS
```

## Preconditions

- Ticket is EXECUTING
- Tool execution, action, workflow, and attempt match
- VerificationId exists

## Flow

1. Deduplicate.
2. Validate result.
3. Save result reference.
4. Start verification.
5. Write history and outbox.
6. Mark processed.
7. Commit.

## Transition

```text
SM-014
```

Tool success never resolves the Ticket directly.

---

# 20. UC-17 Handle Tool Execution Failure

Used when failure is known to have produced no external side effect.

The Ticket returns to INVESTIGATING.

Transition:

```text
SM-015
```

---

# 21. UC-18 Handle Unknown Tool Result

If external side effect is uncertain:

1. Preserve all execution evidence.
2. Escalate the Ticket.
3. Never blindly retry or cancel.

Transition:

```text
SM-017
```

---

# 22. UC-19 Start Verification

Used for:

- No-tool resolution candidates
- Human fixes
- Tool-success follow-up

The Ticket moves to VERIFYING only after a VerificationId is created.

Transitions:

```text
SM-010
SM-032
```

---

# 23. UC-20 Handle Verification Success

## Preconditions

- Ticket is VERIFYING
- Verification matches Ticket, workflow, and latest attempt
- Evidence is complete
- No pending action

## Flow

1. Deduplicate.
2. Build VerificationEvidence.
3. Build TicketResolution.
4. Resolve Ticket.
5. Complete workflow.
6. Mark SLA met.
7. Schedule auto-close.
8. Write history and outbox.
9. Mark event processed.
10. Commit.

## Transition

```text
SM-018
```

## Event

```text
ticket.resolved.v1
```

---

# 24. UC-21 Handle Verification Failure

Branch A:

```text
attemptCount <= 2
→ SM-019 VERIFYING → INVESTIGATING
```

Branch B:

```text
attemptCount > 2 or unsafe result
→ SM-020 VERIFYING → ESCALATED
```

Infrastructure failure may use:

```text
SM-021 VERIFYING → FAILED
```

---

# 25. UC-22 Resolve Ticket

Internal domain/application behavior invoked only by UC-20.

Tool success, agent assertion, or manual flag cannot directly resolve a Ticket.

---

# 26. UC-23 Confirm and Close Ticket

## Actors

```text
EMPLOYEE
IT_SUPPORT
```

## Preconditions

- Ticket is RESOLVED
- Actor is requester or authorized support
- No accepted reopen

## Flow

1. Validate idempotency.
2. Load Ticket.
3. Close with requester-confirmed reason.
4. Write history and outbox.
5. Commit.

## Transition

```text
SM-022
```

---

# 27. UC-24 Auto-close Ticket

## Actor

```text
SYSTEM_SCHEDULER
```

## Preconditions

- Ticket remains RESOLVED for 72 hours
- No requester activity
- No accepted reopen
- Expected version matches

## Flow

1. Select candidate.
2. Deduplicate stable job key.
3. Reload and revalidate.
4. Close with AUTO_CLOSE_TIMEOUT.
5. Write history and outbox.
6. Commit.

## Transition

```text
SM-023
```

---

# 28. UC-25 Reopen Ticket

## Preconditions

- Ticket is RESOLVED, or CLOSED within seven days
- Authorized requester or support
- Reason exists
- New WorkflowId exists
- No old pending action

## Flow

1. Validate idempotency.
2. Validate reopen window.
3. Archive previous cycle.
4. Reopen Ticket.
5. Create new SLA cycle.
6. Associate new workflow.
7. Reset verification attempts.
8. Write history and outbox.
9. Commit.

## Transitions

```text
SM-024
SM-025
```

---

# 29. UC-26 Cancel Ticket

Allowed from:

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
FAILED
ESCALATED
```

The flow invalidates pending actions, requests workflow cancellation, cancels SLA, and writes history and outbox.

If execution is active or side effect is unknown, escalate instead.

Transition:

```text
SM-026
```

---

# 30. UC-27 Escalate Ticket

## Actors

```text
IT_SUPPORT
SYSTEM_POLICY
AGENT_RUNTIME_SERVICE
TOOL_GATEWAY_SERVICE
VERIFICATION_SERVICE
```

## Flow

1. Validate target and reason.
2. Preserve context.
3. Escalate Ticket.
4. Restrict privileged automation.
5. Write history and outbox.
6. Commit.

Transitions include:

```text
SM-017
SM-020
SM-029
SM-033
SM-034
```

---

# 31. UC-28 Assign Ticket

Validates queue and role authorization, updates current assignment, writes assignment history, and emits `ticket.assigned.v1`.

---

# 32. UC-29 Retry Failed Automation

## Preconditions

- Ticket is FAILED
- Retry budget remains
- Failure is transient or resolved
- Workflow path is valid

## Flow

1. Load failure reference.
2. Validate retry policy.
3. Associate recovery or new workflow.
4. Return to INVESTIGATING.
5. Write history and outbox.
6. Commit.

Transition:

```text
SM-028
```

If budget is exhausted, escalate using SM-029.

---

# 33. UC-30 Retrieve Ticket Timeline

Combines:

```text
Status History
Messages
Assignment History
Approval Summary
Tool Execution Summary
Verification Summary
Escalation
Resolution Cycles
SLA History
```

It enforces role-based visibility and returns a cursor-based read model.

Timeline is not a source of truth.

---

# 34. Use Case to Transition Mapping

| Use Case | Transition |
|---|---|
| UC-01 | SM-001 |
| UC-06 | SM-002 |
| UC-07 | SM-003 |
| UC-08 | SM-004 / SM-007 / SM-031 |
| UC-09 | SM-005 / SM-006 |
| UC-11 | SM-008 |
| UC-12 | SM-011 |
| UC-13 | SM-012 |
| UC-14 | SM-013 |
| UC-15 | SM-009 |
| UC-16 | SM-014 |
| UC-17 | SM-015 |
| UC-18 | SM-017 |
| UC-19 | SM-010 / SM-032 |
| UC-20 | SM-018 |
| UC-21 | SM-019 / SM-020 / SM-021 |
| UC-23 | SM-022 |
| UC-24 | SM-023 |
| UC-25 | SM-024 / SM-025 |
| UC-26 | SM-026 |
| UC-27 | SM-017 / SM-020 / SM-029 / SM-033 / SM-034 |
| UC-29 | SM-028 |

---

# 35. Command and Query Catalog

## Commands

```text
CreateTicketCommand
AddTicketMessageCommand
StartTriageCommand
CompleteClassificationCommand
RequestUserInputCommand
ReceiveUserReplyCommand
AssociateWorkflowCommand
RegisterApprovalRequestCommand
HandleApprovalGrantedCommand
HandleApprovalRejectedCommand
HandleApprovalExpiredCommand
StartAutoApprovedExecutionCommand
HandleToolExecutionSuccessCommand
HandleToolExecutionFailureCommand
HandleUnknownToolResultCommand
StartVerificationCommand
HandleVerificationSuccessCommand
HandleVerificationFailureCommand
ConfirmResolutionCommand
AutoCloseTicketCommand
ReopenTicketCommand
CancelTicketCommand
EscalateTicketCommand
AssignTicketCommand
RetryFailedAutomationCommand
```

## Queries

```text
GetTicketQuery
ListRequesterTicketsQuery
ListSupportQueueTicketsQuery
GetTicketTimelineQuery
```

---

# 36. Suggested Application Services

```text
CreateTicketApplicationService
TicketQueryService
TicketMessageApplicationService
TicketWorkflowApplicationService
ApprovalEventApplicationService
ToolResultApplicationService
VerificationApplicationService
TicketClosureApplicationService
TicketEscalationApplicationService
TicketAssignmentApplicationService
TicketTimelineQueryService
```

They coordinate aggregates, repositories, security, idempotency, processed-event records, history, and outbox.

They do not call LLMs, execute tools, publish RabbitMQ messages directly, or export LangSmith traces.

---

# 37. Observability

Each use case records:

```text
use_case.id
ticket.id
workflow.id
command.id or event.id
actor.type
result
error.code
duration
```

Recommended span:

```text
ticket.use_case.execute
```

Ticket ID is not a Prometheus label.

---

# 38. Testing

Every command use case covers:

- Happy path
- Unauthorized
- Invalid input
- Invalid state
- Missing reference
- Duplicate command/event
- Stale event
- Optimistic-lock conflict
- Outbox failure
- Transaction rollback

Queries cover visibility, forbidden access, pagination, redaction, empty results, and read-model lag.

---

# 39. Golden Path

```text
UC-01 Create Ticket
→ UC-06 Start Triage
→ UC-07 Complete Classification
→ UC-11 Request Approval
→ UC-12 Handle Approval Granted
→ UC-16 Handle Tool Execution Success
→ UC-20 Handle Verification Success
→ UC-23 Confirm and Close Ticket
```

Important branches:

```text
UC-08 Request More Information
UC-09 Receive User Reply
UC-13 Approval Rejected
UC-17 Tool Failure
UC-18 Unknown Tool Result
UC-21 Verification Failure
UC-27 Escalate Ticket
```

---

# 40. Acceptance Criteria

- [x] MVP command use cases defined
- [x] MVP query use cases defined
- [x] Actors and authorization defined
- [x] Preconditions and flows defined
- [x] State-machine IDs mapped
- [x] Business invariants referenced
- [x] Transaction boundaries defined
- [x] Domain and integration events defined
- [x] Idempotency and concurrency defined
- [x] Observability and testing defined
- [x] Golden Path use-case chain defined

---

# 41. Next Step

Create:

```text
05-api-contracts/README_CN.md
05-api-contracts/README_EN.md
```

Every API must map to a `UC-xx`. No business API should exist without a supporting use case.
