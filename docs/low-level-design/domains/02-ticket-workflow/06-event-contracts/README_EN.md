# OpsMind Ticket Workflow — 06 Event Contracts

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Asynchronous Event Contract  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependencies:** `04-use-cases/README_EN.md`, `05-api-contracts/README_EN.md`  
> **Message Broker:** RabbitMQ  
> **Schema Standard:** JSON Schema Draft 2020-12  
> **Delivery Semantics:** At-least-once Delivery + Idempotent Consumer  
> **Recommended Path:** `docs/low-level-design/domains/02-ticket-workflow/06-event-contracts/README_EN.md`

---

## 1. Purpose

This document defines asynchronous event contracts between Ticket Workflow and the other OpsMind domains.

It freezes:

- Event envelope
- Event types and routing keys
- Events published by Ticket Workflow
- Events consumed by Ticket Workflow
- Producers and consumers
- Payload fields
- Schema versions
- Aggregate versions
- Trace and correlation metadata
- Ordering
- Idempotency
- Retry
- Dead-letter handling
- Transactional outbox
- PII and secret rules
- Compatibility
- Contract testing
- Manual replay

---

# 2. Core Principles

## 2.1 Events Represent Facts

Good event names:

```text
ticket.created
approval.granted
tool.execution.completed
verification.completed
```

Events are facts that already occurred. Commands are requests to perform work.

## 2.2 Events Never Bypass the Domain

Consumers map events to explicit:

```text
UC-xx
SM-xxx
BI-xxx
```

An event cannot carry an arbitrary target status and directly update `ticket.status`.

## 2.3 Exactly-once Is Not Assumed

OpsMind uses:

```text
At-least-once Delivery
+
Processed Event Store
+
Business Idempotency
+
Optimistic Locking
```

## 2.4 Payloads Are Minimal

Integration events do not serialize complete aggregates, JPA entities, agent workflows, approval entities, credentials, or prompts.

## 2.5 Domain and Integration Events Are Separate

```text
Domain Event:
TicketResolved

Integration Event:
ticket.resolved
```

The Application layer maps the domain event into an integration event and stores it in the Outbox.

---

# 3. Event Types, Routing Keys, and Versions

## Logical Event Type

```json
{
  "eventType": "ticket.created",
  "eventVersion": "1.0"
}
```

## RabbitMQ Routing Key

```text
ticket.created.v1
ticket.resolved.v1
approval.granted.v1
tool.execution.completed.v1
```

Pattern:

```text
<domain>.<fact>.v<major>
```

## Version Rules

`eventVersion` uses `MAJOR.MINOR`.

- Additive optional fields increment Minor.
- Removed fields, changed meaning, or newly required fields increment Major.
- A new Major also uses a new routing key.
- Consumers ignore unknown optional fields.

---

# 4. RabbitMQ Topology

## Main Topic Exchange

```text
opsmind.events
```

```text
type = topic
durable = true
autoDelete = false
```

## Dead Letter Exchange

```text
opsmind.dlx
```

## Ticket Workflow Inbound Queues

```text
ticket-workflow.agent-events.v1
ticket-workflow.approval-events.v1
ticket-workflow.tool-events.v1
ticket-workflow.verification-events.v1
```

Recommended bindings:

```text
ticket-workflow.agent-events.v1
  ← agent.#.v1
  ← ticket.classification.completed.v1

ticket-workflow.approval-events.v1
  ← approval.#.v1
  ← policy.action_auto_approved.v1

ticket-workflow.tool-events.v1
  ← tool.execution.#.v1

ticket-workflow.verification-events.v1
  ← verification.#.v1
```

## DLQs

```text
ticket-workflow.agent-events.dlq.v1
ticket-workflow.approval-events.dlq.v1
ticket-workflow.tool-events.dlq.v1
ticket-workflow.verification-events.dlq.v1
```

## Ordering Strategy

The MVP uses:

```text
x-single-active-consumer = true
```

for state-changing inbound queues.

This reduces concurrent reordering but does not replace workflow, attempt, action, and version validation.

Future scale may use a consistent-hash exchange partitioned by `ticketId`.

---

# 5. Canonical Event Envelope

```json
{
  "eventId": "01J0EVT8H0Z5E6K1W4Q8N7P2M3",
  "eventType": "ticket.created",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-23T16:30:00Z",
  "producer": "ticket-workflow-service",
  "environment": "local",
  "traceId": "8f03d65a4eb64c5b8abf920c56954c31",
  "correlationId": "INC-2048",
  "causationId": "cmd-create-ticket-1001",
  "ticketId": "01J0TICKET...",
  "workflowId": null,
  "aggregateType": "Ticket",
  "aggregateId": "01J0TICKET...",
  "aggregateVersion": 0,
  "sequence": 0,
  "partitionKey": "01J0TICKET...",
  "dataClassification": "INTERNAL",
  "payload": {}
}
```

---

# 6. Envelope Fields

| Field | Type | Required | Description |
|---|---|---:|---|
| eventId | string | yes | Globally unique event ID |
| eventType | string | yes | Logical event type |
| eventVersion | string | yes | `MAJOR.MINOR` |
| occurredAt | date-time | yes | UTC occurrence time |
| producer | string | yes | Producer service |
| environment | string | yes | local / ci / demo / staging / prod |
| traceId | string | yes | OpenTelemetry trace ID |
| correlationId | string | yes | Business correlation identifier |
| causationId | string | no | Triggering command or event ID |
| ticketId | string | yes for ticket flow | Internal Ticket ID |
| workflowId | string/null | no | Current Agent Workflow |
| aggregateType | string | yes | Aggregate type |
| aggregateId | string | yes | Aggregate ID |
| aggregateVersion | integer/null | no | Producer aggregate version |
| sequence | integer/null | no | Aggregate event sequence |
| partitionKey | string | yes | `ticketId` in the MVP |
| dataClassification | enum | yes | PUBLIC / INTERNAL / SENSITIVE |
| payload | object | yes | Event-specific payload |

Passwords, tokens, API keys, cookies, private keys, authorization headers, and raw credentials are forbidden.

---

# 7. Base Envelope JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://opsmind.dev/schemas/events/event-envelope-v1.json",
  "title": "OpsMind Event Envelope v1",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "eventId",
    "eventType",
    "eventVersion",
    "occurredAt",
    "producer",
    "environment",
    "traceId",
    "correlationId",
    "ticketId",
    "aggregateType",
    "aggregateId",
    "partitionKey",
    "dataClassification",
    "payload"
  ],
  "properties": {
    "eventId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "eventType": {
      "type": "string",
      "pattern": "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
    },
    "eventVersion": {
      "type": "string",
      "pattern": "^[1-9][0-9]*\\.[0-9]+$"
    },
    "occurredAt": {
      "type": "string",
      "format": "date-time"
    },
    "producer": {
      "type": "string",
      "minLength": 1,
      "maxLength": 100
    },
    "environment": {
      "type": "string",
      "enum": ["local", "ci", "demo", "staging", "prod"]
    },
    "traceId": {
      "type": "string",
      "minLength": 16,
      "maxLength": 64
    },
    "correlationId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    },
    "causationId": {
      "type": ["string", "null"],
      "maxLength": 128
    },
    "ticketId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "workflowId": {
      "type": ["string", "null"],
      "maxLength": 64
    },
    "aggregateType": {
      "type": "string",
      "minLength": 1,
      "maxLength": 100
    },
    "aggregateId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "aggregateVersion": {
      "type": ["integer", "null"],
      "minimum": 0
    },
    "sequence": {
      "type": ["integer", "null"],
      "minimum": 0
    },
    "partitionKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    },
    "dataClassification": {
      "type": "string",
      "enum": ["PUBLIC", "INTERNAL", "SENSITIVE"]
    },
    "payload": {
      "type": "object"
    }
  }
}
```

---

# 8. RabbitMQ Message Properties

Publishers set:

```text
message_id = eventId
type = eventType
content_type = application/json
content_encoding = utf-8
timestamp = occurredAt
correlation_id = correlationId
delivery_mode = 2
```

Headers:

```text
event_version
traceparent
producer
environment
ticket_id
workflow_id
data_classification
```

The envelope remains authoritative.

---

# 9. Published Event Catalog

| ID | Event Type | Routing Key | Primary Consumers |
|---|---|---|---|
| PUB-001 | ticket.created | ticket.created.v1 | Agent Runtime, SLA, Notification |
| PUB-002 | ticket.triaging_started | ticket.triaging_started.v1 | Agent Runtime, Timeline |
| PUB-003 | ticket.classified | ticket.classified.v1 | Agent Runtime, Analytics |
| PUB-004 | ticket.investigation_ready | ticket.investigation_ready.v1 | Agent Runtime |
| PUB-005 | ticket.user_reply_requested | ticket.user_reply_requested.v1 | Notification, Frontend |
| PUB-006 | ticket.user_replied | ticket.user_replied.v1 | Agent Runtime |
| PUB-007 | ticket.triage_resume_requested | ticket.triage_resume_requested.v1 | Agent Runtime |
| PUB-008 | ticket.investigation_resume_requested | ticket.investigation_resume_requested.v1 | Agent Runtime |
| PUB-009 | ticket.approval_wait_started | ticket.approval_wait_started.v1 | Frontend, Notification |
| PUB-010 | ticket.execution_ready | ticket.execution_ready.v1 | Tool Gateway |
| PUB-011 | ticket.verification_started | ticket.verification_started.v1 | Verification Agent |
| PUB-012 | ticket.resolved | ticket.resolved.v1 | Memory, Evaluation, Notification |
| PUB-013 | ticket.closed | ticket.closed.v1 | Memory, Evaluation, Analytics |
| PUB-014 | ticket.cancelled | ticket.cancelled.v1 | Agent Runtime, Approval, Tool Gateway |
| PUB-015 | ticket.reopened | ticket.reopened.v1 | Agent Runtime, SLA, Evaluation |
| PUB-016 | ticket.escalated | ticket.escalated.v1 | Support UI, Notification, Evaluation |
| PUB-017 | ticket.assigned | ticket.assigned.v1 | Support UI, Notification |
| PUB-018 | ticket.message_added | ticket.message_added.v1 | Timeline, Notification |
| PUB-019 | ticket.approval_rejected | ticket.approval_rejected.v1 | Agent Runtime, Frontend |
| PUB-020 | ticket.approval_expired | ticket.approval_expired.v1 | Agent Runtime, Frontend |
| PUB-021 | ticket.automation_failed | ticket.automation_failed.v1 | Support UI, Observability |
| PUB-022 | ticket.status_changed | ticket.status_changed.v1 | Timeline, Analytics, SLA |

---

# 10. Published Event Contracts

## PUB-001 ticket.created

```json
{
  "displayId": "INC-2048",
  "requesterIdHash": "sha256:...",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "initialStatus": "NEW",
  "createdAt": "2026-07-23T16:30:00Z"
}
```

The event excludes the complete description and requester email. Services needing details use the authorized Internal Context API.

## PUB-002 ticket.triaging_started

```json
{
  "workflowId": "wf-7788",
  "fromStatus": "NEW",
  "toStatus": "TRIAGING",
  "startedAt": "2026-07-23T16:31:00Z"
}
```

## PUB-003 ticket.classified

```json
{
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "classificationSource": "TRIAGE_AGENT",
  "confidence": 0.94,
  "classifiedAt": "2026-07-23T16:33:00Z"
}
```

The broadcast event does not include the complete reasoning.

## PUB-004 ticket.investigation_ready

```json
{
  "workflowId": "wf-7788",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH"
}
```

## PUB-005 ticket.user_reply_requested

```json
{
  "requestId": "req-88",
  "workflowId": "wf-7788",
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "messageId": "msg-20",
  "resumeStatus": "INVESTIGATING",
  "requestedAt": "2026-07-23T16:40:00Z"
}
```

## PUB-006 ticket.user_replied

```json
{
  "requestId": "req-88",
  "messageId": "msg-21",
  "resumeStatus": "INVESTIGATING",
  "repliedAt": "2026-07-23T16:45:00Z"
}
```

Agent Runtime retrieves message content through an authorized API.

## PUB-007 ticket.triage_resume_requested

```json
{
  "workflowId": "wf-7788",
  "requestId": "req-88",
  "messageId": "msg-21",
  "resumeReason": "USER_REPLIED"
}
```

## PUB-008 ticket.investigation_resume_requested

```json
{
  "workflowId": "wf-7788",
  "resumeReason": "USER_REPLIED",
  "sourceReferenceId": "msg-21"
}
```

Supported reasons include user reply, approval rejection or expiration, safe tool failure, verification failure, and automation retry.

## PUB-009 ticket.approval_wait_started

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "riskLevel": "MEDIUM",
  "expiresAt": "2026-07-23T18:00:00Z"
}
```

No credentials or complete tool payloads are included.

## PUB-010 ticket.execution_ready

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "policyDecision": "APPROVED",
  "toolExecutionId": "exec-500",
  "idempotencyKey": "tool-action:act-200"
}
```

Tool Gateway obtains credentials independently and revalidates authorization.

## PUB-011 ticket.verification_started

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-300",
  "resolutionAttemptId": "attempt-2",
  "toolExecutionId": "exec-500",
  "verificationType": "IDENTITY_LOGIN_CHECK",
  "attemptNumber": 1
}
```

## PUB-012 ticket.resolved

```json
{
  "resolutionCycleId": "cycle-1",
  "resolutionCode": "MFA_RESET_SUCCESSFUL",
  "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
  "verificationId": "ver-300",
  "resolvedBy": {
    "actorType": "AGENT",
    "actorId": "verification-agent"
  },
  "resolvedAt": "2026-07-23T17:30:00Z",
  "autoCloseAt": "2026-07-26T17:30:00Z"
}
```

## PUB-013 ticket.closed

```json
{
  "resolutionCycleId": "cycle-1",
  "closeReason": "REQUESTER_CONFIRMED",
  "closedBy": {
    "actorType": "EMPLOYEE",
    "actorIdHash": "sha256:..."
  },
  "closedAt": "2026-07-23T18:00:00Z"
}
```

## PUB-014 ticket.cancelled

```json
{
  "cancelReasonCode": "NO_LONGER_NEEDED",
  "cancelledBy": {
    "actorType": "EMPLOYEE",
    "actorIdHash": "sha256:..."
  },
  "invalidatedActionId": "act-200",
  "workflowCancellationRequested": true,
  "cancelledAt": "2026-07-23T17:00:00Z"
}
```

## PUB-015 ticket.reopened

```json
{
  "previousResolutionCycleId": "cycle-1",
  "newResolutionCycleId": "cycle-2",
  "newWorkflowId": "wf-9000",
  "newSlaCycleId": "sla-cycle-2",
  "reasonCode": "ISSUE_RECURRED",
  "reopenedBy": {
    "actorType": "EMPLOYEE",
    "actorIdHash": "sha256:..."
  },
  "reopenedAt": "2026-07-25T09:00:00Z"
}
```

## PUB-016 ticket.escalated

```json
{
  "targetType": "TEAM",
  "targetId": "SECURITY_SUPPORT",
  "reasonCode": "UNKNOWN_EXTERNAL_SIDE_EFFECT",
  "evidenceReferenceIds": [
    "exec-500",
    "ver-300"
  ],
  "automationRestricted": true,
  "escalatedAt": "2026-07-23T17:10:00Z"
}
```

## PUB-017 ticket.assigned

```json
{
  "teamId": "IDENTITY_SUPPORT",
  "supportUserId": "support-42",
  "assignedBy": {
    "actorType": "IT_MANAGER",
    "actorIdHash": "sha256:..."
  },
  "assignedAt": "2026-07-23T17:10:00Z"
}
```

## PUB-018 ticket.message_added

```json
{
  "messageId": "msg-21",
  "messageType": "USER_MESSAGE",
  "visibility": "REQUESTER_VISIBLE",
  "authorType": "EMPLOYEE",
  "attachmentCount": 1,
  "createdAt": "2026-07-23T16:45:00Z"
}
```

The message body is not included.

## PUB-019 ticket.approval_rejected

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "reasonCode": "INSUFFICIENT_JUSTIFICATION",
  "rejectedAt": "2026-07-23T16:50:00Z"
}
```

## PUB-020 ticket.approval_expired

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "expiredAt": "2026-07-23T18:00:00Z"
}
```

## PUB-021 ticket.automation_failed

```json
{
  "workflowId": "wf-7788",
  "failureReferenceId": "failure-99",
  "failureCategory": "DEPENDENCY_TIMEOUT",
  "retryable": true,
  "retryCount": 1,
  "failedAt": "2026-07-23T17:05:00Z"
}
```

Stack traces are not included.

## PUB-022 ticket.status_changed

```json
{
  "fromStatus": "EXECUTING",
  "toStatus": "VERIFYING",
  "reasonCode": "TOOL_EXECUTION_SUCCEEDED",
  "actorType": "SERVICE",
  "sourceEventId": "evt-tool-500",
  "changedAt": "2026-07-23T17:20:00Z"
}
```

This generic timeline and analytics event does not replace the specific business event.

---

# 11. Consumed Event Catalog

| ID | Event Type | Producer | Mapping |
|---|---|---|---|
| CON-001 | agent.workflow.started | Agent Runtime | UC-06 / SM-002 |
| CON-002 | agent.workflow.failed | Agent Runtime | UC-29 / SM-027 |
| CON-003 | ticket.classification.completed | Agent Runtime | UC-07 / SM-003 |
| CON-004 | agent.user_input_required | Agent Runtime | UC-08 |
| CON-005 | approval.requested | Policy & Approval | UC-11 / SM-008 |
| CON-006 | approval.granted | Policy & Approval | UC-12 / SM-011 |
| CON-007 | approval.rejected | Policy & Approval | UC-13 / SM-012 |
| CON-008 | approval.expired | Policy & Approval | UC-14 / SM-013 |
| CON-009 | policy.action_auto_approved | Policy & Approval | UC-15 / SM-009 |
| CON-010 | tool.execution.completed | Tool Gateway | UC-16 / SM-014 |
| CON-011 | tool.execution.failed | Tool Gateway | UC-17 / SM-015 |
| CON-012 | tool.execution.result_unknown | Tool Gateway | UC-18 / SM-017 |
| CON-013 | agent.resolution_candidate_ready | Agent Runtime | UC-19 / SM-010 |
| CON-014 | verification.completed | Verification Agent | UC-20 / UC-21 |

---

# 12. Consumed Event Contracts

## CON-001 agent.workflow.started

```json
{
  "workflowId": "wf-7788",
  "workflowType": "IDENTITY_SUPPORT",
  "workflowVersion": "1.0.0",
  "startedAt": "2026-07-23T16:31:00Z"
}
```

Guards:

```text
Ticket is NEW
No active workflow
Ticket ID matches
```

Duplicate EventId or WorkflowId is acknowledged as idempotent success.

## CON-002 agent.workflow.failed

```json
{
  "workflowId": "wf-7788",
  "failureReferenceId": "failure-99",
  "failureCategory": "MODEL_TIMEOUT",
  "retryable": true,
  "retryCount": 1,
  "occurredAt": "2026-07-23T17:05:00Z"
}
```

The workflow must match. Unknown external side effects require escalation rather than FAILED.

## CON-003 ticket.classification.completed

```json
{
  "workflowId": "wf-7788",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "confidence": 0.94,
  "source": "TRIAGE_AGENT",
  "classificationAttemptId": "classify-1"
}
```

## CON-004 agent.user_input_required

```json
{
  "workflowId": "wf-7788",
  "requestId": "req-88",
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "message": "Please confirm whether your phone was replaced.",
  "resumeStatus": "INVESTIGATING"
}
```

The requester-visible message must pass content and PII review.

## CON-005 approval.requested

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "riskLevel": "MEDIUM",
  "requestedAt": "2026-07-23T16:42:00Z",
  "expiresAt": "2026-07-23T18:00:00Z"
}
```

## CON-006 approval.granted

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "approvedByIdHash": "sha256:...",
  "approvedAt": "2026-07-23T16:50:00Z",
  "expiresAt": "2026-07-23T18:00:00Z",
  "toolExecutionId": "exec-500"
}
```

The Ticket, Workflow, Action, Action Type, and Approval must all match.

Failure behavior:

| Condition | Behavior |
|---|---|
| Duplicate event | ACK, no second transition |
| Expired approval | Reject application, ACK and metric |
| Old workflow | STALE_EVENT, ACK and audit |
| Wrong action | DLQ or security review |
| Database unavailable | Retry |
| Invalid schema | Immediate DLQ |

## CON-007 approval.rejected

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "reasonCode": "INSUFFICIENT_JUSTIFICATION",
  "rejectedAt": "2026-07-23T16:50:00Z"
}
```

## CON-008 approval.expired

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "expiredAt": "2026-07-23T18:00:00Z"
}
```

## CON-009 policy.action_auto_approved

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-210",
  "actionType": "REFRESH_USER_SESSION",
  "riskLevel": "LOW",
  "policyDecisionId": "policy-dec-300",
  "toolExecutionId": "exec-510",
  "decidedAt": "2026-07-23T16:55:00Z"
}
```

## CON-010 tool.execution.completed

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "toolExecutionId": "exec-500",
  "executionAttemptId": "exec-attempt-1",
  "result": "SUCCESS",
  "resultSummary": {
    "resultCode": "DUO_ENROLLMENT_RESET",
    "changed": true
  },
  "completedAt": "2026-07-23T17:15:00Z",
  "verificationId": "ver-300"
}
```

The result summary is normalized and redacted.

## CON-011 tool.execution.failed

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "toolExecutionId": "exec-500",
  "executionAttemptId": "exec-attempt-1",
  "result": "FAILED",
  "resultCertainty": "KNOWN_NO_SIDE_EFFECT",
  "errorCode": "TARGET_ACCOUNT_NOT_FOUND",
  "retryable": false,
  "failedAt": "2026-07-23T17:15:00Z"
}
```

Only a known no-side-effect failure may return to investigation.

## CON-012 tool.execution.result_unknown

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "toolExecutionId": "exec-500",
  "executionAttemptId": "exec-attempt-1",
  "resultCertainty": "UNKNOWN",
  "errorCode": "TIMEOUT_AFTER_REQUEST_SENT",
  "occurredAt": "2026-07-23T17:15:00Z"
}
```

The Ticket escalates and does not automatically retry or cancel.

## CON-013 agent.resolution_candidate_ready

```json
{
  "workflowId": "wf-7788",
  "resolutionAttemptId": "attempt-2",
  "resolutionCandidate": {
    "resolutionCode": "USER_GUIDANCE_SUCCESSFUL",
    "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
    "summary": "User completed re-enrollment."
  },
  "verificationId": "ver-300",
  "createdAt": "2026-07-23T17:20:00Z"
}
```

## CON-014 verification.completed

Success:

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-300",
  "resolutionAttemptId": "attempt-2",
  "attemptNumber": 1,
  "result": "SUCCESS",
  "evidenceSummary": {
    "checkType": "LOGIN_TEST",
    "resultCode": "AUTHENTICATION_SUCCEEDED"
  },
  "resolution": {
    "resolutionCode": "MFA_RESET_SUCCESSFUL",
    "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
    "summary": "Duo enrollment was reset and login verification succeeded."
  },
  "completedAt": "2026-07-23T17:30:00Z"
}
```

Failure:

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-301",
  "resolutionAttemptId": "attempt-2",
  "attemptNumber": 2,
  "result": "FAILURE",
  "failureCode": "LOGIN_STILL_FAILS",
  "retryable": true,
  "unsafe": false,
  "completedAt": "2026-07-23T17:35:00Z"
}
```

Ticket, Workflow, Verification, Resolution Attempt, and latest-cycle references must match.

---

# 13. Consumer Processing Algorithm

```text
1. Receive message
2. Validate content type
3. Parse JSON
4. Validate envelope schema
5. Validate payload schema
6. Validate event type and major version
7. Continue OpenTelemetry context
8. Check Processed Event Store
9. Load Ticket
10. Validate Ticket, Workflow, Action, and Attempt
11. Validate source state and invariants
12. Apply Use Case and state transition
13. Save Ticket
14. Insert history
15. Insert Outbox Events
16. Insert Processed Event Record
17. Commit
18. ACK
```

---

# 14. ACK, Retry, and DLQ Classification

## ACK without Retry

```text
Duplicate event
Stale event from an old workflow
Already-applied result
Expired approval that cannot apply
Late legitimate event invalidated by current state
```

These still produce audit logs and metrics.

## Retry

```text
Temporary database outage
Optimistic-lock conflict after re-evaluation
Transient internal dependency
Likely out-of-order event
```

## Immediate DLQ

```text
Invalid JSON
Schema violation
Unknown major version
Missing identity fields
Corrupt Ticket or Action mismatch
Forbidden secret detected
```

---

# 15. Retry Policy

Retry queues:

```text
ticket-workflow.retry.5s.v1
ticket-workflow.retry.30s.v1
ticket-workflow.retry.5m.v1
```

Maximum:

```text
3 retries
```

Headers:

```text
x-opsmind-retry-count
x-opsmind-last-error-code
x-opsmind-first-failed-at
```

After the final retry, the message goes to the DLQ.

---

# 16. Out-of-order Handling

If `tool.execution.completed` arrives before `approval.granted`:

```text
Validate current state
→ bounded retry
→ reconciliation
→ DLQ
```

The consumer never forces the Ticket into VERIFYING.

---

# 17. Processed Event Store

Unique key:

```text
consumerName + eventId
```

Suggested fields:

```text
consumerName
eventId
eventType
eventVersion
ticketId
workflowId
payloadHash
processedAt
processingResult
aggregateVersionAfter
```

Results:

```text
APPLIED
DUPLICATE
STALE
REJECTED_BUSINESS_RULE
```

The processed-event record and business update commit in one transaction.

---

# 18. Payload Hash

Consumers compute:

```text
SHA-256(canonical JSON)
```

If the same EventId arrives with a different payload hash:

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
→ Immediate DLQ
→ Security Alert
```

---

# 19. Transactional Outbox

Publishing transaction:

```text
BEGIN
Update Ticket
Insert Status History
Insert Outbox Event
COMMIT
```

Outbox Publisher:

```text
Read unpublished rows
Publish with Publisher Confirm
Mark published
```

Suggested fields:

```text
outboxId
eventId
eventType
eventVersion
routingKey
aggregateId
aggregateVersion
ticketId
workflowId
payload
headers
createdAt
publishedAt
publishAttempts
lastPublishError
```

---

# 20. Publisher Confirm and Duplicate Publication

An Outbox record is marked published only after a broker confirm.

A crash after broker receipt and before marking may publish the same Event again. Consumers therefore rely on `eventId` idempotency.

---

# 21. Manual Replay

## Replay of the Original Event

Preserve original EventId, payload, and occurredAt.

Add headers:

```text
x-opsmind-replayed
x-opsmind-replay-operator
x-opsmind-replay-time
```

## Corrected Event

A corrected payload uses:

- A new EventId
- `causationId` referencing the original
- `correctionOfEventId`
- Immutable original records

---

# 22. Schema Repository

```text
packages/event-contracts/
├── common/
│   └── event-envelope-v1.schema.json
├── ticket/
│   ├── published/
│   │   ├── ticket-created-v1.schema.json
│   │   ├── ticket-resolved-v1.schema.json
│   │   ├── ticket-closed-v1.schema.json
│   │   └── ...
│   └── consumed/
│       ├── approval-granted-v1.schema.json
│       ├── tool-execution-completed-v1.schema.json
│       ├── verification-completed-v1.schema.json
│       └── ...
└── examples/
```

---

# 23. Compatibility Rules

Compatible:

- New optional field
- Expanded descriptions
- Additional non-breaking metadata
- Example fixes

Potentially breaking:

- New enum values
- Nullable to non-null
- Unit changes
- Time-semantics changes

Breaking:

- Remove required field
- Rename field
- Change type
- Change event meaning
- Make an optional field required
- Change ID semantics

Breaking changes require a new Major Version and routing key.

---

# 24. Security and PII

Data classification:

```text
PUBLIC
INTERNAL
SENSITIVE
```

Events never carry `SECRET` data.

Potentially sensitive fields include hashed requester identifiers, resolution summaries, and evidence summaries. They are minimized and redacted.

Publisher and consumer logs do not print the complete payload.

Allowed diagnostic fields:

```text
eventId
eventType
ticketId
workflowId
aggregateVersion
payloadHash
```

---

# 25. OpenTelemetry

Producer span:

```text
messaging.publish
```

Consumer span:

```text
messaging.process
```

Attributes:

```text
messaging.system = rabbitmq
messaging.destination.name
messaging.operation.type
messaging.message.id
opsmind.event_type
opsmind.event_version
opsmind.ticket_id
opsmind.workflow_id
opsmind.aggregate_version
opsmind.processing_result
```

Ticket ID is not a Prometheus label.

---

# 26. Metrics

```text
ticket_event_published_total
ticket_event_publish_failed_total
ticket_event_consumed_total
ticket_event_processing_failed_total
ticket_event_duplicate_total
ticket_event_stale_total
ticket_event_out_of_order_total
ticket_event_schema_invalid_total
ticket_event_dlq_total
ticket_event_replayed_total
ticket_outbox_pending_count
ticket_outbox_oldest_age_seconds
```

Allowed low-cardinality labels:

```text
event_type
producer
consumer
result
error_category
```

Forbidden labels:

```text
ticket_id
workflow_id
event_id
requester_id
```

---

# 27. Contract Testing

## Producer Tests

Validate:

- Envelope schema
- Event-specific schema
- Routing key
- Required headers
- No secret fields
- Example payload
- Domain event mapping
- Aggregate version

## Consumer Tests

Validate:

- Valid event consumption
- Unknown optional fields are ignored
- Missing required fields are rejected
- Unknown Major Version goes to DLQ
- Duplicate event is idempotent
- Same EventId with a different payload is rejected
- Stale workflow event does not change Ticket
- Out-of-order event retries
- Business update and processed record commit atomically

## Compatibility Tests

CI classifies schema changes as:

```text
additive-compatible
potentially-breaking
breaking
```

A breaking change fails unless a new Major Version is introduced.

---

# 28. Critical Tests

```text
shouldPublishTicketCreatedWithoutDescription
shouldPublishTicketResolvedWithVerificationReference
shouldConsumeApprovalGrantedExactlyOnce
shouldRejectApprovalForDifferentAction
shouldIgnoreLateVerificationFromOldWorkflow
shouldRetryOutOfOrderToolCompletedEvent
shouldDlqInvalidSchema
shouldDlqReusedEventIdWithDifferentPayload
shouldRollbackProcessedEventWhenTicketUpdateFails
shouldRepublishOutboxEventAfterPublisherCrash
shouldNotLogSensitivePayload
shouldPreserveTraceContextAcrossRabbitMq
```

---

# 29. Event to Use Case and State Machine Mapping

| Event | Use Case | State Machine |
|---|---|---|
| agent.workflow.started | UC-06 | SM-002 |
| ticket.classification.completed | UC-07 | SM-003 |
| agent.user_input_required | UC-08 | SM-004 / SM-007 |
| approval.requested | UC-11 | SM-008 |
| approval.granted | UC-12 | SM-011 |
| approval.rejected | UC-13 | SM-012 |
| approval.expired | UC-14 | SM-013 |
| policy.action_auto_approved | UC-15 | SM-009 |
| tool.execution.completed | UC-16 | SM-014 |
| tool.execution.failed | UC-17 | SM-015 |
| tool.execution.result_unknown | UC-18 | SM-017 |
| agent.resolution_candidate_ready | UC-19 | SM-010 |
| verification.completed SUCCESS | UC-20 | SM-018 |
| verification.completed FAILURE | UC-21 | SM-019 / SM-020 |
| agent.workflow.failed | UC-29 | SM-027 |

---

# 30. Rejected Event Designs

## Generic ticket.status_update_requested

Rejected because it bypasses business semantics.

## Complete Ticket in Every Event

Rejected because it leaks PII, couples the domain model, and creates large payloads.

## RabbitMQ Redelivery as the Only Idempotency Mechanism

Rejected because it cannot cover producer duplicate publication or crash recovery.

## Trusting Queue Order Without Reference Validation

Rejected because retries, multiple queues, and concurrent producers can reorder events.

## Calling External Tools from Ticket Consumers

Rejected. Consumers update Ticket state and publish the next event through the Outbox.

---

# 31. Acceptance Criteria

- [x] Canonical envelope defined
- [x] Event type and routing-key rules defined
- [x] Exchanges, queues, and DLQs defined
- [x] Published events defined
- [x] Consumed events defined
- [x] Payload examples defined
- [x] Idempotency and payload hash defined
- [x] Ordering and retry defined
- [x] Transactional outbox defined
- [x] Publisher confirms defined
- [x] Manual replay defined
- [x] Security, PII, and redaction defined
- [x] OpenTelemetry and metrics defined
- [x] Contract and compatibility testing defined
- [x] Events mapped to use cases and state transitions

---

# 32. Next Step

Create:

```text
07-data-model/README_CN.md
07-data-model/README_EN.md
```

The data model will map Ticket aggregates, messages, history, SLA, outbox, processed events, and idempotency records into PostgreSQL.
