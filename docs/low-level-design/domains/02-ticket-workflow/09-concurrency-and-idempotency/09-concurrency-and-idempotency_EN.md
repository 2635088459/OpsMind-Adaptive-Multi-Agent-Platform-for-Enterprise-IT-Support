# OpsMind Ticket Workflow — 09 Concurrency and Idempotency

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Concurrency and Idempotency Design  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependencies:** `02-business-invariants_EN.md`, `03-state-machine_EN.md`, `04-use-cases_EN.md`, `05-api-contracts_EN.md`, `06-event-contracts_EN.md`, `07-data-model_EN.md`, `08-transaction-and-outbox_EN.md`  
> **Concurrency Model:** Optimistic Concurrency + Database Constraints + Idempotent Commands + Idempotent Consumers  
> **Recommended Path:** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/09-concurrency-and-idempotency_EN.md`

---

## 1. Purpose

This document defines how Ticket Workflow produces one legal, explainable, and recoverable result when users, services, threads, instances, schedulers, and RabbitMQ deliveries operate concurrently.

It freezes:

- HTTP command idempotency
- Internal command idempotency
- Event consumer deduplication
- Canonical request and payload hashes
- Optimistic locking
- Aggregate versions
- Event sequences
- Duplicate, stale, and out-of-order classification
- Multi-instance concurrency
- Queue-consumer concurrency
- Scheduler concurrency
- Race-condition decision tables
- Retry and re-evaluation
- Recovery and reconciliation
- Database constraints as the final defense
- Metrics, alerts, and tests

Core goals:

```text
Repeated delivery of the same business intent produces at most one business effect.
Competing intents allow only a result legal under the current state machine.
Late and old-cycle events cannot contaminate the current processing cycle.
Every retry reloads state and re-evaluates business invariants.
```

---

# 2. Sources of Concurrency

## Client Retries

- Double-clicks
- Browser timeouts
- Mobile reconnects
- Repeated cancel or reopen requests

## Multiple Support Users

- Concurrent assignments
- Cancel versus escalate
- Close versus reopen

## Competing Service Events

- Approval granted versus cancellation
- Tool success versus unknown result
- Verification completion versus reopen
- Workflow failure versus verification success

## RabbitMQ Duplicate and Reordering

- Duplicate producer publication
- Consumer commit before ACK crash
- Retry queues
- Different producer and queue ordering

## Scheduler Concurrency

- Multiple auto-close workers
- Auto-close versus reopen
- SLA breach versus resolve
- Multiple cleanup workers

## Multiple Outbox Publishers

Several publisher instances may claim and publish concurrently.

---

# 3. Consistency Goals

## Single-Aggregate Serializability by Effect

For one Ticket, committed outcomes are equivalent to some legal serial order that satisfies the state machine and invariants.

Implemented through:

```text
Expected Version
+
Atomic Transaction
+
Unique and Partial Unique Constraints
+
Guard Re-evaluation
```

## Effectively-once Business Effect

RabbitMQ may deliver more than once, but the business result is applied at most once.

## No Lost Update

Every current Ticket update uses an expected aggregate version. Last-write-wins is forbidden.

## No Cross-cycle Contamination

Events from an old resolution cycle, workflow, action, execution attempt, or verification attempt cannot change the current cycle.

---

# 4. Identity Hierarchy

| Layer | Identifier | Purpose |
|---|---|---|
| HTTP Request | `idempotencyKey` | Command deduplication |
| Command | `commandId` | Trace, audit, internal identity |
| Event | `eventId` | Consumer deduplication |
| Ticket | `ticketId` | Concurrency scope |
| Snapshot | `version` | Lost-update protection |
| Resolution Cycle | `resolutionCycleId` | Distinguishes reopen cycles |
| Agent Workflow | `workflowId` | Current agent graph |
| Pending Action | `actionId` | Business action identity |
| Approval | `approvalId` | Approval decision |
| Tool Execution | `toolExecutionId` | Execution identity |
| Tool Attempt | `executionAttemptId` | Tool retry identity |
| Resolution Attempt | `resolutionAttemptId` | Proposed resolution identity |
| Verification | `verificationId` | Verification job |
| Verification Attempt | `attemptNumber` | Verification retry counter |
| Scheduler | `jobKey` | Scheduled-command deduplication |

TicketId alone is never sufficient to validate an event.

---

# 5. Commands Requiring Idempotency

Public commands:

```text
Create Ticket
Add Ticket Message
Cancel Ticket
Reopen Ticket
Confirm Resolution
```

Support commands:

```text
Request User Input
Assign Ticket
Escalate Ticket
Retry Automation
Support Close
```

Internal commands:

```text
Start Triage
Complete Classification
Associate Workflow
Start Verification
```

Internal operations use a stable command ID or source EventId.

---

# 6. Idempotency-Key Scope

Unique constraint:

```text
actor_scope + idempotency_key
```

Recommended scopes:

```text
user:{subject}:{operationFamily}
support:{subject}:{operationFamily}
service:{clientId}:{operationFamily}
scheduler:{jobType}
```

The actor scope is derived from trusted authentication context.

---

# 7. Canonical Request Hash

The hash detects reuse of one key for a different business payload.

Input:

```text
HTTP method
normalized route template
actor scope
canonical JSON body
selected semantic headers
```

Excluded:

```text
JWT
traceparent
correlation ID
request timestamp
header ordering
JSON field ordering
irrelevant whitespace
```

Canonical JSON rules:

- Sort object keys.
- Use UTF-8.
- Preserve array order unless the field is explicitly a set.
- Distinguish null from absence.
- Normalize timestamps to UTC.
- Apply DTO defaults before hashing.
- Use stable numeric formatting.

Hash:

```text
SHA-256(canonical request)
```

---

# 8. HTTP Command Idempotency Algorithm

```text
1. Authenticate
2. Build actor scope
3. Normalize request
4. Compute request hash
5. Begin transaction
6. Try to reserve an IN_PROGRESS record
7. If reserved:
       execute the use case
       store the response
       mark COMPLETED
       commit
       return
8. If an existing record is found:
       compare hash
       different → IDEMPOTENCY_KEY_REUSED
       COMPLETED → return stored response
       fresh IN_PROGRESS → REQUEST_IN_PROGRESS
       stale IN_PROGRESS → reconcile
       FAILED_RETRYABLE → reserve recovery
       FAILED_FINAL → return stored final error
```

---

# 9. Idempotent Response Semantics

A replay returns the original committed result whenever possible:

```text
same HTTP status
same resource ID
same primary response body
```

A new trace ID is allowed.

A replay never creates another Ticket, Message, Workflow, Cycle, Outbox Event, or Tool Execution.

Optional response header:

```http
Idempotency-Replayed: true
```

---

# 10. Concurrent `IN_PROGRESS` Records

A database unique constraint allows only one request to reserve the key.

A second request normally returns:

```http
409 Conflict
Retry-After: 1
```

with:

```text
REQUEST_IN_PROGRESS
```

The MVP does not hold the second request open while waiting.

Stale threshold:

```text
5 minutes
```

A stale record requires reconciliation rather than an assumption of failure.

---

# 11. Idempotency Reconciliation

For stale `IN_PROGRESS`:

1. Check the related resource.
2. Check matching history.
3. Check matching outbox records.
4. Determine whether the business transaction committed.
5. If committed, reconstruct and store the response.
6. If not committed, mark retryable and allow execution.
7. If uncertain, block, alert, and avoid creating a second resource.

---

# 12. Event Deduplication Algorithm

```text
1. Validate envelope and payload
2. Compute payload hash
3. Begin transaction
4. Lookup consumerName + eventId
5. Existing record:
       different hash → rollback, DLQ, alert
       same hash → duplicate ACK
6. No record:
       load Ticket
       classify event
       apply or record stale/rejected
       insert Processed Event
       commit
       ACK
```

---

# 13. Duplicate Event

An event is a transport duplicate only when:

```text
same consumerName
same eventId
same payloadHash
```

It does not update Ticket, write history, or create new outbox events.

---

# 14. Event ID Reuse

The same EventId with a different payload is a producer contract and security violation:

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
Immediate DLQ
Security Alert
```

The consumer never guesses which payload is correct.

---

# 15. Business Duplicate

Different EventIds may represent the same business fact.

Stable identifiers prevent duplicate effects:

```text
approvalId
actionId
toolExecutionId
verificationId
workflowId
resolutionAttemptId
```

For example, a second EventId granting the same already-applied ApprovalId is a business duplicate.

---

# 16. Aggregate Version

Ticket version starts at zero and increments for every core aggregate change.

Core changes include:

- Lifecycle state
- Category and priority
- Assignment
- Active workflow
- Current resolution cycle
- Pending-action snapshot
- Resolution, close, cancel, and reopen
- Open user-request reference

A normal message that does not change Ticket state may avoid incrementing Ticket version. A message that resumes WAITING_FOR_USER must increment it.

SLA has its own independent version.

---

# 17. Expected Version Sources

API:

```http
If-Match: "<ticketVersion>"
```

Internal command:

```text
expectedVersion
```

Event consumers load the current Ticket and update using that loaded version.

Producer aggregate versions are used for diagnostics and ordering, not blindly as the Ticket expected version.

---

# 18. Optimistic Lock Algorithm

```text
attempt = 0

while attempt < 3:
    load Ticket
    check whether already applied
    validate state and references
    compute transition
    update where version = loadedVersion

    success:
        write history and outbox
        commit
        return

    rollback
    increment attempt
    jittered backoff

reload and re-evaluate

already applied → idempotent success
no longer legal → stale, invalid state, or conflict
otherwise → concurrent update or retry queue
```

A retry always reloads and re-runs guards.

---

# 19. Classification Order

Consumers classify events in this order:

```text
1. Valid schema?
2. Duplicate EventId?
3. EventId payload conflict?
4. Ticket exists?
5. Ticket reference matches?
6. Resolution cycle matches?
7. Workflow matches?
8. Action, approval, execution, and verification references match?
9. Business effect already applied?
10. Source state legal?
11. Predecessor potentially missing?
12. Apply, stale ACK, retry, or DLQ
```

Identity is checked before state.

---

# 20. Stale Events

Typical stale conditions:

- Old workflow
- Old resolution cycle
- Replaced or invalidated action
- Old approval
- Old tool execution
- Old verification
- Old resolution attempt
- Terminal Ticket receiving an old-cycle event

Stale events are recorded for audit and ACKed. They do not retry forever.

---

# 21. Out-of-order Events

An event is out of order when it belongs to the current intent but a required predecessor has not yet been applied.

Example:

```text
tool.execution.completed arrives while the Ticket is still WAITING_FOR_APPROVAL,
but workflow and action references match the current pending action
```

Handling:

```text
bounded retry
→ reconciliation
→ DLQ
```

The consumer never skips state-machine steps.

---

# 22. Corrupt or Suspicious Events

Examples:

- Ticket and action ownership mismatch
- One toolExecutionId linked to two actions
- Approval action type mismatch
- EventId payload conflict
- Forbidden secret in payload
- One verification ID linked to different attempts

Handling:

```text
Immediate DLQ
Integrity or security alert
No Ticket update
```

---

# 23. Aggregate Version in Events

Ticket Workflow publishes:

```text
aggregateVersion = committed Ticket version
sequence = position within the same transaction
```

External aggregate versions belong to the producer aggregate and cannot be directly compared with Ticket version.

The MVP does not maintain a complete offset table for every external aggregate. It relies on stable references, state, deduplication, and reconciliation.

---

# 24. Multi-instance API Concurrency

Correctness does not depend on in-process locks.

It depends on:

```text
PostgreSQL unique constraints
Optimistic locking
Idempotency records
Partial unique indexes
```

A JVM `synchronized` block may optimize but never provide the only correctness guarantee.

---

# 25. Multi-instance Consumer Concurrency

The MVP recommends:

```text
x-single-active-consumer = true
```

for state-changing queues.

However, different queues, schedulers, and APIs can still compete on one Ticket. Database concurrency control remains authoritative.

Future partitioning may use a consistent-hash exchange keyed by TicketId.

---

# 26. Database Constraint Defense

Critical constraints include:

```text
UNIQUE(actor_scope, idempotency_key)
PRIMARY KEY(consumer_name, event_id)
UNIQUE(ticket_id, aggregate_version) for status history
one open user request
one active pending action
one active SLA cycle
UNIQUE(ticket_id, cycle_number)
UNIQUE(tool_execution_id)
```

After a constraint violation, the application reloads and determines whether the competing transaction produced an equivalent result or a true conflict.

---

# 27. Race: Duplicate Ticket Creation

Two identical create requests with the same key create only one Ticket.

One reserves the key; the other returns the completed result or `REQUEST_IN_PROGRESS`.

---

# 28. Race: Duplicate Messages

The same idempotency key creates one Message.

Different keys with identical content create two messages because content-based deduplication may remove legitimate communication.

For replies to one open request, only the first reply resumes the workflow; later replies remain normal messages.

---

# 29. Race: Cancel versus Approval Granted

Cancel first:

```text
Ticket → CANCELLED
Pending Action → INVALIDATED
Late approval → stale or rejected and ACKed
```

Approval first:

```text
Ticket → EXECUTING
Cancel becomes illegal
```

Optimistic locking chooses one legal winner.

---

# 30. Race: Cancel versus Auto-approved Action

The first committed transition wins.

An action that enters EXECUTING blocks direct cancellation. A cancelled Ticket rejects the late auto-approved event as stale.

---

# 31. Race: Approval Granted versus Rejected

One ApprovalId must not have two terminal decisions.

The first result may apply. A conflicting later terminal result raises:

```text
APPROVAL_TERMINAL_RESULT_CONFLICT
```

and requires DLQ or security review.

---

# 32. Race: Approval Granted versus Expired

If grant commits first and `approvedAt <= expiresAt`, execution proceeds and the later expiry is stale.

If expiry commits first, the pending action expires and a later grant is rejected and audited.

---

# 33. Race: Tool Success versus Tool Failure

One `toolExecutionId + executionAttemptId` has one terminal result.

A conflicting later result triggers:

```text
TOOL_TERMINAL_RESULT_CONFLICT
```

It is not treated as an ordinary stale event.

---

# 34. Race: Tool Success versus Unknown Result

Unknown first:

```text
Ticket → ESCALATED
```

A later success becomes evidence and does not automatically leave ESCALATED.

Success first:

```text
Ticket → VERIFYING
```

A later unknown result does not silently reverse state; it triggers conflict handling and possibly an explicit escalation command.

---

# 35. Race: Verification Success versus Failure

One VerificationId has one terminal result.

Conflicting results trigger:

```text
VERIFICATION_TERMINAL_RESULT_CONFLICT
```

Only the current verification and resolution attempt may affect the Ticket.

---

# 36. Race: Verification Success versus Reopen

Reopen is valid only from RESOLVED or CLOSED.

A request based on an old VERIFYING snapshot fails If-Match. After verification resolves, the user may reload and explicitly reopen.

---

# 37. Race: Reopen versus Auto-close

Reopen first invalidates the auto-close expected version.

Auto-close first closes the Ticket, after which reopen may still succeed within seven days.

Both outcomes are legal serializations.

---

# 38. Race: Confirm Resolution versus Auto-close

Both perform RESOLVED to CLOSED.

The first commit wins. The second returns idempotent success without a second history or event.

The first close reason remains authoritative.

---

# 39. Race: Close versus Reopen

Close first may be followed by reopen within the window.

Reopen first makes a concurrent close based on the old version invalid.

---

# 40. Race: Two Assignments

Two different concurrent assignments do not use last-write-wins.

One commits; the other receives a conflict after reload and requires explicit user action.

---

# 41. Race: Assignment versus Escalation

One transition commits first.

The second reloads and applies only if still legal. It never silently overwrites the first assignment or escalation target.

---

# 42. Race: User Reply versus Cancel

Reply first may resume to INVESTIGATING, after which cancel can still be explicitly requested.

Cancel first keeps the Ticket cancelled. The MVP may store the later message but does not resume the workflow.

---

# 43. Race: Multiple User Replies

Only the first valid response marks the request ANSWERED and publishes a resume event.

Later responses remain messages and do not resume again.

---

# 44. Race: Workflow Failure versus Tool Result

If a tool has started, workflow failure cannot blindly mark the Ticket FAILED.

Unknown side effects escalate. A committed tool success continues to verification. Old failure events become stale evidence.

---

# 45. Race: SLA Breach versus Resolve

Resolve first marks SLA met and causes the breach worker to stop.

Breach first records `breachedAt`; later resolution sets status to MET while retaining breach history.

---

# 46. Race: Multiple Auto-close Workers

A stable job key and expected version allow one worker to close the Ticket.

Other workers reload and return idempotent success.

---

# 47. Race: Multiple Outbox Publishers

`FOR UPDATE SKIP LOCKED` normally gives each row to one publisher.

Lock recovery may still cause duplicate publication, so consumer idempotency remains required.

---

# 48. Retry Classification

Automatic retry is allowed for:

- Transient database errors
- Deadlocks
- Serialization failures
- Re-evaluated optimistic conflicts
- Out-of-order events
- Transient broker failures
- Publisher confirm timeouts

Automatic retry is not allowed for:

- Invalid schema
- Authorization failure
- Business-rule failure
- EventId payload conflict
- Terminal-result conflicts
- Secret leakage
- Expired reopen window
- Cancellation with unknown side effect

---

# 49. Retry Budgets

Service-level database retries:

```text
maximum 3
```

Event consumers:

```text
3 immediate attempts
then 5s, 30s, and 5m retry queues
then DLQ
```

Outbox publication:

```text
maximum 10 automatic attempts
```

Schedulers naturally retry on later scans and isolate failures per Ticket.

---

# 50. Identity Preservation During Retry

Retries preserve:

```text
idempotencyKey
commandId
eventId
ticketId
workflowId
actionId
toolExecutionId
verificationId
resolutionAttemptId
jobKey
```

A corrected payload requires a new EventId and correction metadata.

---

# 51. Reconciliation

Reconciliation is required for:

- Unknown tool result followed by success
- Long-lived out-of-order events
- Stale IN_PROGRESS idempotency records
- Long-unpublished outbox rows
- Conflicting approval results
- Conflicting verification results
- Snapshot and history version mismatch

Possible outcomes:

```text
NO_ACTION
MARK_STALE
REAPPLY_SAFE
ESCALATE
CREATE_CORRECTION_EVENT
MANUAL_REVIEW_REQUIRED
```

Every reconciliation action is audited.

---

# 52. Generic Event Classification Algorithm

```text
function classify(event, ticket, processedRecord):
    if processedRecord exists:
        if hash differs:
            return CORRUPT_EVENT_ID_REUSE
        return DUPLICATE

    if ticket reference differs:
        return CORRUPT_REFERENCE

    if cycle differs:
        return STALE

    if workflow differs:
        return STALE

    if action or attempt references conflict:
        return CORRUPT_REFERENCE or STALE

    if business effect already applied:
        return BUSINESS_DUPLICATE

    if source state is legal:
        return APPLY

    if current references match and a predecessor may be missing:
        return OUT_OF_ORDER

    if terminal Ticket and event belongs to a completed cycle:
        return STALE

    return REJECTED_BUSINESS_RULE
```

---

# 53. Approval Classification

```text
Duplicate EventId → DUPLICATE
Same approval already applied → BUSINESS_DUPLICATE
Old workflow or action → STALE
Wrong action type → CORRUPT_REFERENCE
Valid WAITING_FOR_APPROVAL → APPLY
Possible missing predecessor → OUT_OF_ORDER
Cancelled or closed Ticket → STALE or REJECTED
```

---

# 54. Tool Classification

```text
Same execution and same terminal result → BUSINESS_DUPLICATE
Same attempt and conflicting result → TERMINAL_RESULT_CONFLICT
Old workflow, action, or execution → STALE
Valid EXECUTING state → APPLY
Matching intent but still WAITING_FOR_APPROVAL → OUT_OF_ORDER
ESCALATED after unknown result → EVIDENCE_ONLY or RECONCILE
Closed or cancelled → STALE
```

---

# 55. Verification Classification

```text
Same verification and same result → BUSINESS_DUPLICATE
Same verification and conflicting result → TERMINAL_RESULT_CONFLICT
Old workflow, cycle, or attempt → STALE
Valid VERIFYING state → APPLY
Arrives before verification state → OUT_OF_ORDER
Already resolved by same verification → BUSINESS_DUPLICATE
Reopened to new cycle → STALE
```

---

# 56. API Error Semantics

| Condition | HTTP | Code |
|---|---:|---|
| Same key, completed request | Original | Success |
| Same key, different payload | 409 | IDEMPOTENCY_KEY_REUSED |
| Same key, still processing | 409 | REQUEST_IN_PROGRESS |
| Expected version mismatch | 412 | CONCURRENT_UPDATE |
| State changed and command illegal | 422 | INVALID_STATE_TRANSITION |
| Equivalent result already committed | 200/201 | Idempotent replay |
| Integrity conflict | 409/500 | DATA_INTEGRITY_CONFLICT |

---

# 57. Broker Semantics

| Classification | Result |
|---|---|
| APPLY | Commit then ACK |
| DUPLICATE | ACK |
| BUSINESS_DUPLICATE | Record and ACK |
| STALE | Record and ACK |
| REJECTED_BUSINESS_RULE | ACK or DLQ by policy |
| OUT_OF_ORDER | Retry |
| TRANSIENT_FAILURE | Retry |
| CORRUPT_REFERENCE | DLQ |
| EVENT_ID_REUSE | DLQ |
| TERMINAL_RESULT_CONFLICT | DLQ |
| SECRET_DETECTED | DLQ |

---

# 58. Observability

Recommended spans:

```text
ticket.idempotency.reserve
ticket.idempotency.replay
ticket.concurrency.update
ticket.event.classify
ticket.event.deduplicate
ticket.reconciliation.execute
```

Metrics:

```text
ticket_command_idempotency_replay_total
ticket_command_idempotency_conflict_total
ticket_command_in_progress_total
ticket_optimistic_conflict_total
ticket_optimistic_retry_total
ticket_business_duplicate_total
ticket_event_duplicate_total
ticket_event_stale_total
ticket_event_out_of_order_total
ticket_event_terminal_conflict_total
ticket_event_reference_corruption_total
ticket_reconciliation_total
ticket_reconciliation_manual_review_total
```

Only low-cardinality labels such as operation, event type, classification, and result are allowed.

---

# 59. Alerts

Critical:

```text
EventId reused with different payload
Tool terminal-result conflict
Verification terminal-result conflict
Corrupt cross-reference
Growing manual-reconciliation backlog
```

Warnings:

```text
High optimistic-conflict rate
High out-of-order rate
Stale IN_PROGRESS records
Sudden duplicate spike
```

---

# 60. Security

- Raw idempotency keys are not logged.
- Actor scope is derived from authentication.
- Payload hashes use canonical JSON.
- EventId payload conflicts are security incidents.
- Old If-Match values never bypass current state.
- Replay and reconciliation record operator identity.
- Corrupt references are not automatically repaired.
- Secret detection is fail-closed.

---

# 61. Unit Tests

```text
shouldCanonicalizeEquivalentJsonRequestsToSameHash
shouldTreatNullAndMissingFieldAccordingToSchema
shouldRejectSameIdempotencyKeyWithDifferentHash
shouldReturnStoredResponseForCompletedRequest
shouldReturnRequestInProgressForFreshReservation
shouldReconcileStaleInProgressRecord

shouldClassifySameEventIdAndHashAsDuplicate
shouldClassifySameEventIdDifferentHashAsCorrupt
shouldClassifyOldWorkflowEventAsStale
shouldClassifyMissingPredecessorAsOutOfOrder
shouldClassifyConflictingToolTerminalResultAsCorrupt
shouldClassifySameVerificationResultAsBusinessDuplicate

shouldReloadAndReevaluateAfterOptimisticConflict
shouldNotBlindlyRetryInvalidTransition
shouldPreserveIdentityAcrossRetry
```

---

# 62. Integration Tests

```text
shouldCreateOnlyOneTicketForConcurrentIdenticalRequests
shouldCreateTwoTicketsForDifferentIdempotencyKeys
shouldAllowOnlyOneConcurrentAssignment
shouldResolveCancelApprovalRace
shouldResolveApprovalExpiryGrantRace
shouldRejectConflictingToolResults
shouldRejectConflictingVerificationResults
shouldResumeWorkflowOnlyOnceForMultipleReplies
shouldCloseOnlyOnceForMultipleAutoCloseWorkers
shouldHandleReopenAutoCloseRace
shouldKeepOldVerificationFromAffectingReopenedCycle
shouldDeduplicateEventAcrossConsumerRestart
shouldHandleDifferentQueuesUpdatingSameTicket
shouldEnforceOneActivePendingActionUnderConcurrency
shouldEnforceOneOpenUserRequestUnderConcurrency
```

---

# 63. Load and Stress Tests

Simulate:

```text
100 concurrent create requests with one key
100 concurrent assignments to one Ticket
Random approval and cancel ordering
Shuffled tool success, failure, and unknown events
Mixed old and current verification events
Multiple consumers and schedulers
Duplicate Outbox publication
```

Verify:

- No duplicate business resources
- No lost updates
- No illegal states
- Continuous history versions
- Outbox consistency
- Bounded deadlock retries
- Expected duplicate and stale metrics

---

# 64. Rejected Alternatives

- Last-write-wins
- Frontend button disabling as the only protection
- RabbitMQ redelivery flag as the only deduplication
- TicketId-only event validation
- Content-hash deduplication of all messages
- JVM local locks as correctness
- Blind optimistic-lock retries
- ACKing all conflicts as stale
- Distributed locks as a replacement for idempotency

---

# 65. Acceptance Criteria

- [x] Concurrency sources defined
- [x] Command idempotency scope defined
- [x] Canonical request hash defined
- [x] HTTP idempotency algorithm defined
- [x] Stale IN_PROGRESS recovery defined
- [x] Event deduplication defined
- [x] Business duplicates defined
- [x] Aggregate and expected versions defined
- [x] Optimistic retry defined
- [x] Duplicate, stale, and out-of-order ordering defined
- [x] Corrupt events and terminal-result conflicts defined
- [x] Multi-instance API, consumer, scheduler, and publisher strategies defined
- [x] Critical race outcomes frozen
- [x] Retry budgets and reconciliation defined
- [x] API and broker semantics defined
- [x] Observability, alerts, security, and tests defined

---

# 66. Next Step

Recommended next document:

```text
10-error-handling-and-reconciliation_CN.md
10-error-handling-and-reconciliation_EN.md
```

It will define error taxonomy, retryability, DLQ triage, reconciliation workflows, manual recovery, compensating actions, and user-visible versus internal error mapping.
