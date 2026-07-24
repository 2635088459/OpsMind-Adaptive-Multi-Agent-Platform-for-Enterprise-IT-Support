# OpsMind Ticket Workflow — 10 Error Handling and Reconciliation

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Error Handling, Recovery, and Reconciliation Design  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependencies:** `03-state-machine_EN.md`, `04-use-cases_EN.md`, `05-api-contracts_EN.md`, `06-event-contracts_EN.md`, `07-data-model_EN.md`, `08-transaction-and-outbox_EN.md`, `09-concurrency-and-idempotency_EN.md`  
> **Recommended Path:** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/10-error-handling-and-reconciliation_EN.md`

---

## 1. Purpose

This document defines consistent handling for business errors, concurrency conflicts, dependency failures, messaging failures, data inconsistencies, and security incidents in Ticket Workflow.

It freezes:

- Error taxonomy
- Error-code naming and structure
- Retryable and non-retryable decisions
- Domain, application, and infrastructure mappings
- HTTP error responses
- RabbitMQ ACK, retry, and DLQ decisions
- Automatic recovery
- Reconciliation cases
- DLQ triage
- Manual recovery
- Compensating actions
- User-visible versus internal errors
- Audit and operator authorization
- Observability, metrics, and alerts
- Runbooks and failure drills
- Error-handling tests

Core goals:

```text
The same failure category receives consistent treatment.
Non-recoverable errors are not retried forever.
Recoverable errors are not permanently lost after one failure.
Unknown side effects are never silently retried or hidden.
Manual recovery never bypasses the state machine, approval, idempotency, or audit.
```

---

# 2. Design Principles

## Structured Errors

Expected failures have stable:

```text
errorCode
category
retryability
severity
audience
source
```

Free-form exception messages are not contracts.

## User and Internal Errors Are Separate

Users receive an understandable message, next step, and trace reference.

They never receive stack traces, SQL, hostnames, queue names, raw dependency responses, secrets, prompts, or policy internals.

## Fail Closed

If the system cannot confirm approval, side effects, verification identity, authorization, event integrity, or secret safety, it blocks high-risk continuation.

## Retry Is Not the Default

Before retrying, the system determines:

```text
Is the operation idempotent?
Could the previous attempt have produced a side effect?
Is the current business state still legal?
Will the retry preserve identity?
Is retry budget available?
```

## Reconciliation Is Not Generic Data Editing

Reconciliation reads authoritative state, preserves evidence, produces a decision, invokes legal use cases, and records operator actions.

## Compensation Is a New Business Action

A compensating action is explicit, auditable, policy-evaluated, and independently verified. It is not a database rollback.

---

# 3. Error Taxonomy

| Category | Meaning | Default Retry |
|---|---|---:|
| `VALIDATION` | Invalid request or event schema | no |
| `AUTHENTICATION` | Missing or invalid identity | no |
| `AUTHORIZATION` | Actor lacks permission | no |
| `BUSINESS_RULE` | Invariant violation | no |
| `STATE_CONFLICT` | Operation illegal in current state | no |
| `CONCURRENCY` | Version or lock conflict | conditional |
| `IDEMPOTENCY` | Key, EventId, or payload conflict | conditional |
| `REFERENCE` | Workflow, action, approval, or attempt mismatch | conditional |
| `ORDERING` | Required predecessor is missing | bounded retry |
| `DEPENDENCY_TRANSIENT` | Temporary dependency failure | yes |
| `DEPENDENCY_PERMANENT` | Permanent dependency error | no |
| `MESSAGING` | Publish, confirm, route, or DLQ issue | conditional |
| `DATA_INTEGRITY` | Snapshot, history, or constraint inconsistency | manual |
| `SECURITY` | Secret, tampering, unauthorized, suspicious reference | no |
| `RESOURCE_EXHAUSTION` | Rate, pool, disk, or memory pressure | conditional |
| `UNKNOWN` | Unclassified exception | no by default |

---

# 4. Severity

```text
INFO
WARNING
ERROR
CRITICAL
FATAL
```

Severity does not independently determine retryability.

Examples:

- INFO: expected invalid transition
- WARNING: transient database issue
- ERROR: one Ticket cannot proceed
- CRITICAL: payload conflict or secret leak
- FATAL: migration or ownership corruption

---

# 5. Retryability

```text
NOT_RETRYABLE
RETRY_IMMEDIATE
RETRY_WITH_BACKOFF
RETRY_AFTER_RECONCILIATION
MANUAL_ONLY
```

Immediate retry is limited to safe transient failures such as deadlocks or re-evaluated optimistic conflicts.

Backoff retry is used for temporary broker, database, ordering, or rate-limit failures.

Unknown side effects, terminal-result conflicts, integrity failures, and security incidents require reconciliation or manual action.

---

# 6. Canonical Error Descriptor

```text
ErrorDescriptor
├── code
├── category
├── severity
├── retryability
├── sourceLayer
├── userMessageKey
├── operatorMessage
├── httpStatus?
├── brokerDisposition?
├── alertPolicy
├── auditRequired
└── safeDetails
```

Example:

```json
{
  "code": "TOOL_RESULT_UNKNOWN",
  "category": "REFERENCE",
  "severity": "ERROR",
  "retryability": "RETRY_AFTER_RECONCILIATION",
  "sourceLayer": "INTEGRATION",
  "userMessageKey": "ticket.processing_requires_review",
  "operatorMessage": "Tool execution result could not be confirmed.",
  "httpStatus": 202,
  "brokerDisposition": "ACK_AND_OPEN_RECONCILIATION",
  "alertPolicy": "WARNING",
  "auditRequired": true
}
```

---

# 7. Error Code Naming

Format:

```text
<DOMAIN>_<SUBJECT>_<CONDITION>
```

Examples:

```text
TICKET_NOT_FOUND
INVALID_STATE_TRANSITION
CONCURRENT_UPDATE
IDEMPOTENCY_KEY_REUSED
REQUEST_IN_PROGRESS
WORKFLOW_REFERENCE_MISMATCH
APPROVAL_REFERENCE_MISMATCH
TOOL_RESULT_UNKNOWN
TOOL_TERMINAL_RESULT_CONFLICT
VERIFICATION_TERMINAL_RESULT_CONFLICT
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
OUTBOX_PUBLISH_RETRY_EXHAUSTED
DATA_INTEGRITY_CONFLICT
```

Opaque codes such as `ERR_01` are forbidden.

---

# 8. Source Layers

```text
DOMAIN
APPLICATION
API
PERSISTENCE
MESSAGING
INTEGRATION
SECURITY
SCHEDULER
RECONCILIATION
```

Each layer translates its native failures into stable error semantics.

---

# 9. Domain Errors

Domain errors represent illegal behavior under current aggregate state:

```text
INVALID_STATE_TRANSITION
CANCELLATION_NOT_ALLOWED
VERIFICATION_REQUIRED
REOPEN_WINDOW_EXPIRED
ACTIVE_WORKFLOW_ALREADY_EXISTS
```

The Domain layer does not choose HTTP status or RabbitMQ disposition.

---

# 10. Application Errors

The Application layer converts domain, persistence, and integration failures into use-case outcomes.

It decides whether to roll back, record a processed event, open reconciliation, publish escalation, or return an API error.

---

# 11. Infrastructure Errors

Driver and vendor exceptions are translated into stable types:

```text
DatabaseUnavailable
DeadlockDetected
OptimisticLockConflict
OutboxInsertFailed
RabbitPublishFailed
PublisherConfirmTimeout
UnroutableMessage
SchemaValidationFailed
DependencyTimeout
DependencyRateLimited
```

Raw vendor exceptions never cross the controller or consumer boundary.

---

# 12. Spring Exception Mapping

Suggested hierarchy:

```text
TicketDomainException
TicketApplicationException
TicketInfrastructureException
TicketSecurityException
```

REST uses `@RestControllerAdvice`.

Event consumers produce an `EventProcessingDecision` instead of blindly throwing every exception to the container.

---

# 13. HTTP Mapping

| Error Code | HTTP |
|---|---:|
| `VALIDATION_ERROR` | 400 |
| `UNAUTHENTICATED` | 401 |
| `FORBIDDEN` | 403 |
| `TICKET_NOT_FOUND` | 404 |
| `IDEMPOTENCY_KEY_REUSED` | 409 |
| `REQUEST_IN_PROGRESS` | 409 |
| `DATA_INTEGRITY_CONFLICT` | 409 / 500 |
| `CONCURRENT_UPDATE` | 412 |
| `INVALID_STATE_TRANSITION` | 422 |
| `CANCELLATION_NOT_ALLOWED` | 422 |
| `REOPEN_WINDOW_EXPIRED` | 422 |
| `RATE_LIMITED` | 429 |
| `DEPENDENCY_UNAVAILABLE` | 503 |
| `INTERNAL_ERROR` | 500 |

---

# 14. User-visible Error Envelope

```json
{
  "error": {
    "code": "CANCELLATION_NOT_ALLOWED",
    "message": "This ticket cannot be cancelled while an action is being executed.",
    "traceId": "8f03d65a...",
    "correlationId": "INC-2048",
    "retryable": false,
    "nextAction": "Wait for the current action to finish or contact IT support."
  }
}
```

User-visible fields:

```text
code
message
traceId
correlationId
retryable
retryAfterSeconds?
nextAction?
```

Internal implementation data is excluded.

---

# 15. User Message Keys

```text
ticket.not_found
ticket.concurrent_update
ticket.cancellation_not_allowed
ticket.reopen_window_expired
ticket.processing_temporarily_unavailable
ticket.processing_requires_review
ticket.request_in_progress
```

The frontend localizes these keys. The backend supplies a safe default English message.

---

# 16. Internal-to-User Mapping

| Internal Error | User Message |
|---|---|
| Database connection failure | Service temporarily unavailable |
| Rabbit confirm timeout | Request accepted; processing may be delayed |
| Tool result unknown | Ticket requires IT review |
| EventId payload conflict | Generic internal error |
| Invalid transition | Action unavailable in current status |
| Stale event | Not shown |
| Out-of-order event | Not shown |
| DLQ replay failure | Generic internal error |

---

# 17. API Failure Rules

Before commit:

- Roll back.
- Return a safe error.
- Do not produce success outbox events.

After commit but before response:

- Same Idempotency-Key returns stored success.

Broker unavailable after business and outbox commit:

- Command may still return success.
- Publisher retries later.

---

# 18. Event Processing Decision

```text
EventProcessingDecision
├── classification
├── brokerDisposition
├── processedEventResult
├── retryDelay
├── reconciliationRequired
├── alertSeverity
└── errorCode
```

Broker dispositions:

```text
ACK
RETRY
DLQ
ACK_AND_RECONCILE
```

---

# 19. Broker Decision Matrix

| Classification | Disposition |
|---|---|
| APPLY | Commit then ACK |
| DUPLICATE | ACK |
| BUSINESS_DUPLICATE | Record then ACK |
| STALE | Record then ACK |
| REJECTED_BUSINESS_RULE | ACK, or DLQ if security-sensitive |
| OUT_OF_ORDER | Retry |
| TRANSIENT_FAILURE | Retry |
| TOOL_RESULT_UNKNOWN | ACK and reconcile |
| CORRUPT_REFERENCE | DLQ |
| EVENT_ID_REUSE | DLQ |
| TERMINAL_RESULT_CONFLICT | DLQ |
| SECRET_DETECTED | DLQ |
| UNKNOWN_EXCEPTION | Limited retry, then DLQ |

---

# 20. Retry Policy

Immediate retry:

```text
maximum 3
10–100ms jitter
```

Broker retry:

```text
5s
30s
5m
```

A dependency retry considers idempotency, timeout phase, possible side effects, Retry-After, and circuit state.

Retry-budget exhaustion opens reconciliation or escalates.

---

# 21. Circuit Breaker

Synchronous external dependencies may use:

```text
CLOSED
OPEN
HALF_OPEN
```

An open circuit blocks new calls and returns a temporary error or moves work to an asynchronous path.

Circuit breakers do not replace retry budgets or idempotency.

---

# 22. Timeout Policy

| Operation | Recommended |
|---|---:|
| PostgreSQL command transaction | 3s |
| Event consumer transaction | 5s |
| Internal read API | 2s |
| Workflow provisioning | 3s |
| RabbitMQ confirm | 5s |
| Reconciliation external read | 5s |
| Operator recovery command | 10s |

A timeout does not prove that a remote side effect did not happen.

---

# 23. Reconciliation Definition

Reconciliation verifies facts and restores consistency through legal business actions.

Triggers include:

- Unknown tool result
- Conflicting terminal results
- Long-lived out-of-order events
- Exhausted outbox publication
- Stale idempotency reservations
- Snapshot/history mismatch
- Missing resolution cycle
- DLQ review

---

# 24. Reconciliation Case

```text
ReconciliationCase
├── reconciliationId
├── ticketId
├── resolutionCycleId
├── type
├── status
├── severity
├── sourceErrorCode
├── sourceEventId?
├── sourceCommandId?
├── evidenceReferences
├── proposedDecision?
├── finalDecision?
├── assignedTeam?
├── assignedOperator?
├── createdAt
├── updatedAt
├── resolvedAt?
└── version
```

---

# 25. Reconciliation Types

```text
TOOL_RESULT_UNKNOWN
TOOL_TERMINAL_RESULT_CONFLICT
VERIFICATION_TERMINAL_RESULT_CONFLICT
APPROVAL_TERMINAL_RESULT_CONFLICT
OUT_OF_ORDER_EVENT
OUTBOX_PUBLISH_FAILURE
IDEMPOTENCY_IN_PROGRESS_STALE
DATA_INTEGRITY_MISMATCH
CROSS_REFERENCE_CORRUPTION
DLQ_REVIEW
SECURITY_REVIEW
```

---

# 26. Reconciliation Status

```text
OPEN
INVESTIGATING
WAITING_FOR_EXTERNAL_FACT
WAITING_FOR_APPROVAL
RECOVERY_READY
RECOVERY_EXECUTING
RESOLVED
DISMISSED
FAILED
```

Status changes use dedicated reconciliation use cases.

---

# 27. Reconciliation Outcomes

```text
NO_ACTION
MARK_EVENT_STALE
REPLAY_ORIGINAL_EVENT
CREATE_CORRECTION_EVENT
REAPPLY_SAFE_TRANSITION
ESCALATE_TICKET
REQUEST_NEW_APPROVAL
EXECUTE_COMPENSATION
REBUILD_IDEMPOTENCY_RESPONSE
REPAIR_DERIVED_RECORD
MANUAL_REVIEW_REQUIRED
SECURITY_INCIDENT
```

---

# 28. Reconciliation Workflow

```text
1. Create case
2. Restrict unsafe automation when required
3. Collect immutable evidence
4. Read authoritative external state
5. Compare Ticket snapshot and history
6. Determine current business truth
7. Propose recovery
8. Require approval when risk requires
9. Execute through normal use cases or events
10. Verify recovery
11. Record final decision
12. Resolve case
```

---

# 29. Evidence Rules

Evidence references may include:

```text
eventId
commandId
workflowId
actionId
approvalId
toolExecutionId
verificationId
historyId
outboxId
traceId
externalAuditReference
```

Evidence is immutable, timestamped, source-identified, redacted, and access-controlled.

---

# 30. Unknown Tool Result

Initial handling:

```text
Ticket → ESCALATED
automationRestricted = true
open reconciliation case
```

Investigation checks Tool Gateway records and the target-system audit log.

Outcomes:

- Confirmed applied: start verification.
- Confirmed not applied: return to investigation and create a new execution attempt if needed.
- Still unknown: manual review.

The original tool execution is never blindly repeated.

---

# 31. Tool Terminal-result Conflict

Success and failure for the same execution attempt cause:

```text
DLQ
Critical reconciliation
Automation freeze
Tool source-of-truth query
```

The final fact is published as a correction event. Original events remain immutable.

---

# 32. Verification Conflict

A conflicting VerificationId:

1. Blocks auto-resolution.
2. Opens a critical case.
3. Validates test-run and evidence identity.
4. Starts a new VerificationId if needed.
5. Resolves only after a new trusted success.

---

# 33. Approval Conflict

For conflicting grant, reject, or expiry:

- Stop tool execution if it has not started.
- If execution started, reconcile the tool result.
- Query the Approval domain source of truth.
- Preserve approver audit references.
- Publish a correction event.

---

# 34. Out-of-order Reconciliation

After all retry levels fail:

1. Query producer records.
2. Search event archive for the predecessor.
3. Inspect Processed Event Store.
4. Determine whether the predecessor was lost, unpublished, already applied, or never occurred.
5. Replay, correct, mark stale, or require manual review.

---

# 35. Idempotency Reconciliation

For stale `IN_PROGRESS`:

- Inspect resource, history, outbox, and stored response.
- Rebuild `COMPLETED` if business commit occurred.
- Mark retryable if it did not occur.
- Open a case if the result remains uncertain.

The record is not simply deleted.

---

# 36. Data-integrity Reconciliation

Examples:

```text
Ticket without current resolution cycle
History version gap
WAITING_FOR_APPROVAL without pending action
WAITING_FOR_USER without open request
RESOLVED without verification
CLOSED without resolution
Outbox aggregate-version mismatch
```

Derived records may be repaired through controlled commands or migrations. Business state is never guessed and directly edited.

---

# 37. DLQ Triage

Required metadata:

```text
eventId
eventType
eventVersion
routingKey
producer
ticketId
workflowId
payloadHash
firstFailedAt
lastFailedAt
retryCount
lastErrorCode
traceId
originalHeaders
```

Priority:

- P0: secret or cross-ticket corruption
- P1: terminal-result conflicts
- P2: persistent ordering or integrity failures
- P3: schema or version errors
- P4: harmless stale events

---

# 38. DLQ Workflow

```text
NEW
→ CLASSIFIED
→ INVESTIGATING
→ READY_TO_REPLAY
→ REPLAYED
→ VERIFIED
→ RESOLVED
```

Alternatives:

```text
DISMISSED
SECURITY_INCIDENT
MANUAL_RECOVERY_REQUIRED
```

---

# 39. Replay Rules

Original replay preserves the EventId, payload, and occurredAt, while adding replay operator and reconciliation metadata.

A changed payload requires a new EventId and:

```text
causationId
correctionOfEventId
```

Original events are immutable.

---

# 40. Manual Recovery

Manual recovery uses dedicated commands:

```text
MarkEventStaleCommand
ReplayEventCommand
CreateCorrectionEventCommand
ResumeInvestigationCommand
StartNewVerificationCommand
RequestNewApprovalCommand
ExecuteCompensationCommand
ResolveReconciliationCommand
```

Direct SQL state mutation is forbidden.

---

# 41. Recovery Authorization

| Action | Minimum Authority |
|---|---|
| View reconciliation | Support or Auditor |
| Replay ordinary event | IT Admin |
| Create correction event | IT Admin + Domain Owner |
| Execute compensation | IT Admin + Approval |
| Handle security case | Security Admin |
| Repair data | DB Admin + Application Owner |
| Resolve case | Assigned operator or manager |

High-risk recovery uses four-eyes approval.

---

# 42. Recovery Audit

Each manual recovery records:

```text
operatorId
approverId?
reconciliationId
ticketId
actionType
reasonCode
beforeSnapshotHash
afterSnapshotHash
sourceEvidence
commandId
occurredAt
result
```

Audit records are append-only.

---

# 43. Compensating Actions

A compensation reverses or mitigates an external side effect.

Examples:

- Restore removed access
- Restore device configuration
- Re-enable an account
- Undo an incorrect license assignment

Rules:

- It must be a supported Tool Catalog action.
- It receives a new ActionId and ToolExecutionId.
- It runs through policy and approval.
- It receives independent verification.
- It references `compensatesActionId`.

Irreversible, unknown, or high-risk cases require manual handling.

---

# 44. Compensation State Flow

```text
ESCALATED
→ INVESTIGATING
→ WAITING_FOR_APPROVAL
→ EXECUTING
→ VERIFYING
→ RESOLVED
```

Compensation never bypasses the Ticket state machine.

---

# 45. Partial Failures

Ticket commit plus notification failure:

- Ticket remains committed.
- Notification retries independently.

Resolved Ticket plus Memory failure:

- Ticket remains resolved.
- Memory consumer retries.

Tool success plus verification-start failure:

- Ticket does not resolve.
- Verification is restarted without repeating the tool.

Approval success plus Tool Gateway outage:

- The same action identity is retained.
- The system retries dispatch or escalates after budget exhaustion.

---

# 46. Error Budgets

| Stage | Budget |
|---|---:|
| Optimistic conflict | 3 |
| Database transient retry | 3 |
| Consumer immediate retry | 3 |
| Broker retry queues | 3 levels |
| Outbox publication | 10 |
| Verification failure | 2; third escalates |
| Workflow automation | recommended 2 |
| Automatic reconciliation | 1–3 by type |

No workflow retries forever.

---

# 47. Unknown Errors

An unclassified exception:

1. Maps to `INTERNAL_ERROR`.
2. Rolls back the transaction.
3. Records a safe fingerprint.
4. Hides implementation details from users.
5. Receives limited consumer retry.
6. Goes to DLQ after exhaustion.
7. Raises an alert.
8. Requires taxonomy improvement.

High-risk recovery is not automatic.

---

# 48. Error Fingerprint

```text
SHA-256(
  exception family
  + application operation
  + normalized top stack frames
  + dependency
  + error code
)
```

Fingerprints exclude Ticket IDs, requester data, secrets, and dynamic message bodies.

---

# 49. Observability

Spans:

```text
ticket.error.handle
ticket.retry.execute
ticket.reconciliation.open
ticket.reconciliation.investigate
ticket.reconciliation.recover
ticket.dlq.triage
ticket.compensation.execute
```

Attributes:

```text
opsmind.error_code
opsmind.error_category
opsmind.severity
opsmind.retryability
opsmind.retry_count
opsmind.reconciliation_id
opsmind.recovery_outcome
opsmind.broker_disposition
```

---

# 50. Metrics

```text
ticket_error_total
ticket_error_retryable_total
ticket_error_non_retryable_total
ticket_retry_attempt_total
ticket_retry_exhausted_total
ticket_reconciliation_open_total
ticket_reconciliation_resolved_total
ticket_reconciliation_failed_total
ticket_reconciliation_age_seconds
ticket_dlq_message_total
ticket_dlq_replay_total
ticket_dlq_replay_failed_total
ticket_compensation_requested_total
ticket_compensation_completed_total
ticket_compensation_failed_total
ticket_unknown_error_total
```

Only low-cardinality labels are allowed.

---

# 51. Alerts

Critical:

```text
Security error
EventId payload conflict
Cross-ticket reference corruption
Tool or verification terminal conflict
P0 reconciliation backlog
Oldest unpublished outbox over five minutes
```

Warnings:

```text
Retry exhaustion rising
DLQ backlog rising
Reconciliation age over target
Unknown fingerprint spike
Transient dependency failure rate rising
```

---

# 52. Reconciliation SLA

| Priority | Acknowledge | Resolution Target |
|---|---:|---:|
| P0 | 15 min | 4 h |
| P1 | 30 min | 8 h |
| P2 | 4 h | 2 business days |
| P3 | 1 business day | 5 business days |
| P4 | 2 business days | best effort |

This operational SLA is separate from the requester Ticket SLA.

---

# 53. Operator Runbooks

Every stable Error Code has a runbook containing:

```text
Meaning
User impact
Automatic behavior
Data to inspect
Safe queries
Unsafe actions
Replay eligibility
Compensation eligibility
Escalation team
Verification steps
Closure criteria
```

---

# 54. Safe Diagnostics

Operators may read Ticket snapshot, history, current cycle, pending action, processed event, outbox event, reconciliation case, and authorized external references.

Unreviewed production write SQL is prohibited.

---

# 55. Testing

Unit tests cover descriptor mapping, retry decisions, safe user messages, reconciliation creation, correction events, and compensation approval.

Integration tests cover rollback, stale ACK, ordering retry, DLQ, idempotency response rebuild, replay, correction events, manual-recovery audit, and prevention of direct state mutation.

Chaos tests cover broker failure, database restart, tool timeout after request submission, conflicting verification, conflicting approval, and prolonged outbox failure.

---

# 56. Error Injection

Controlled test faults:

```text
FAIL_OUTBOX_INSERT
FAIL_AFTER_TICKET_UPDATE
FAIL_BEFORE_TRANSACTION_COMMIT
FAIL_AFTER_COMMIT_BEFORE_HTTP_RESPONSE
FAIL_AFTER_CONSUMER_COMMIT_BEFORE_ACK
FAIL_BEFORE_PUBLISH_CONFIRM
RETURN_UNROUTABLE_MESSAGE
RETURN_TOOL_RESULT_UNKNOWN
RETURN_CONFLICTING_VERIFICATION
```

Fault injection is enabled only in local, CI, or demo environments behind explicit feature flags.

---

# 57. Acceptance Criteria

- [x] Error taxonomy defined
- [x] Severity and retryability defined
- [x] Canonical descriptor defined
- [x] Error-code naming defined
- [x] Domain, application, and infrastructure layers separated
- [x] HTTP and user-visible errors defined
- [x] ACK, retry, and DLQ matrix defined
- [x] Retry, circuit-breaker, and timeout policies defined
- [x] Reconciliation model, type, status, and outcomes defined
- [x] Tool, verification, approval, ordering, idempotency, and integrity reconciliation defined
- [x] DLQ triage and replay defined
- [x] Manual recovery, authorization, and audit defined
- [x] Compensation defined
- [x] Partial failures and error budgets defined
- [x] Observability, alerts, runbooks, and tests defined

---

# 58. Data-model Increment

A later `07-data-model` revision should add:

```text
ticket.reconciliation_cases
ticket.reconciliation_evidence
ticket.recovery_audit_records
```

A lightweight operational implementation is acceptable for the MVP, but reconciliation cannot rely only on logs or chat messages.

---

# 59. Next Step

Recommended next document:

```text
11-security-and-authorization_CN.md
11-security-and-authorization_EN.md
```

It will define Keycloak roles and scopes, resource ownership, queue-based access, internal service identity, approval trust boundaries, PII redaction, secret handling, audit authorization, threat modeling, and abuse cases.
