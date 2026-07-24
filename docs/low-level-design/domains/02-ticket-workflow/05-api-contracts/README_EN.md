# OpsMind Ticket Workflow — 05 API Contracts

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level REST API Contract  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependency:** `04-use-cases/README_EN.md`  
> **Standard:** REST + OpenAPI 3.1  
> **Recommended Path:** `docs/low-level-design/domains/02-ticket-workflow/05-api-contracts/README_EN.md`

---

## 1. Purpose

This document defines the synchronous HTTP contracts for Ticket Workflow, including:

- Public employee APIs
- Support and administrator APIs
- Internal service APIs
- Request and response schemas
- Authentication and authorization
- Idempotency
- Optimistic concurrency
- Pagination
- Error envelopes
- PII visibility
- Mapping to `UC-xx` and `SM-xxx`

It is designed to be directly convertible into an OpenAPI 3.1 specification.

---

# 2. Core API Principles

## 2.1 Every Business API Maps to a Use Case

The service does not expose generic state mutation:

```http
POST /api/v1/tickets/{ticketId}/change-status
POST /internal/v1/tickets/{ticketId}/transitions
```

Reasons:

- Callers cannot choose arbitrary target states.
- Generic mutation bypasses guards, invariants, and the state machine.
- State changes must use business-semantic commands.

Examples:

```http
POST /api/v1/tickets/{ticketId}/cancel
POST /api/v1/tickets/{ticketId}/reopen
POST /internal/v1/tickets/{ticketId}/triage/start
```

## 2.2 Asynchronous Results Use Events

The following results primarily enter Ticket Workflow through RabbitMQ:

```text
approval.granted
approval.rejected
approval.expired
tool.execution.completed
tool.execution.failed
tool.execution.result_unknown
verification.completed
agent.workflow.failed
```

The service does not expose public HTTP callbacks for these state results.

## 2.3 APIs Do Not Expose Domain Entities

Contracts use DTOs, never direct serialization of:

```text
Ticket Aggregate
JPA Entity
Domain Event
```

## 2.4 API and Event Versions Are Independent

```text
Public API: /api/v1
Internal API: /internal/v1
Event Version: 1.0
```

---

# 3. Base URLs

```text
Public:
https://{host}/api/v1

Internal:
https://{host}/internal/v1
```

Local development:

```text
http://localhost:8080/api/v1
http://localhost:8080/internal/v1
```

---

# 4. Common Headers

## 4.1 Authorization

```http
Authorization: Bearer <JWT>
```

- Public and Support APIs use Keycloak user access tokens.
- Internal APIs use OAuth 2.0 client-credentials tokens.

## 4.2 Trace and Correlation

```http
traceparent: 00-<trace-id>-<span-id>-01
X-Correlation-Id: <correlation-id>
```

The service generates a correlation ID when none is supplied.

## 4.3 Idempotency

Protected commands use:

```http
Idempotency-Key: <unique-key>
```

Rules:

- Length: 1–128 characters
- Unique within actor or client scope
- Same key and payload return the original result
- Same key and different payload return `IDEMPOTENCY_KEY_REUSED`

## 4.4 Optimistic Concurrency

Commands modifying an existing Ticket use:

```http
If-Match: "7"
```

Responses return:

```http
ETag: "8"
```

A version mismatch returns:

```http
412 Precondition Failed
```

with:

```text
CONCURRENT_UPDATE
```

## 4.5 Content Type

```http
Content-Type: application/json
Accept: application/json
```

---

# 5. ID, Time, and Enum Conventions

Time:

```text
ISO 8601 UTC
2026-07-23T16:30:00Z
```

Internal IDs:

```text
UUID or ULID
```

Display ID:

```text
INC-2048
```

Paths use internal `ticketId`; the UI displays `displayId`.

---

# 6. Error Envelope

```json
{
  "error": {
    "code": "INVALID_STATE_TRANSITION",
    "message": "Ticket cannot be cancelled while tool execution is active.",
    "traceId": "5f6d7a...",
    "correlationId": "INC-2048",
    "details": {
      "currentStatus": "EXECUTING",
      "allowedStatuses": [
        "NEW",
        "TRIAGING",
        "INVESTIGATING",
        "WAITING_FOR_USER",
        "WAITING_FOR_APPROVAL",
        "FAILED",
        "ESCALATED"
      ]
    }
  }
}
```

## Status Codes

| HTTP | Meaning |
|---|---|
| 200 | Query or command success |
| 201 | Resource created |
| 202 | Command accepted for asynchronous continuation |
| 204 | Success without response body |
| 400 | Request schema error |
| 401 | Unauthenticated |
| 403 | Forbidden |
| 404 | Resource missing or invisible |
| 409 | Business conflict |
| 412 | `If-Match` version conflict |
| 422 | Invalid business semantics |
| 429 | Rate limited |
| 500 | Unknown internal error |
| 503 | Temporarily unavailable |

Primary error codes:

```text
VALIDATION_ERROR
TICKET_NOT_FOUND
FORBIDDEN
FORBIDDEN_QUEUE_ACCESS
INVALID_TICKET_STATE
INVALID_STATE_TRANSITION
ACTIVE_WORKFLOW_ALREADY_EXISTS
WORKFLOW_REFERENCE_MISMATCH
APPROVAL_REFERENCE_MISMATCH
ACTION_REFERENCE_MISMATCH
VERIFICATION_REQUIRED
REOPEN_NOT_ALLOWED
REOPEN_WINDOW_EXPIRED
CANCELLATION_NOT_ALLOWED
CONCURRENT_UPDATE
IDEMPOTENCY_KEY_REUSED
RATE_LIMITED
INTERNAL_ERROR
```

---

# 7. Common Response Schemas

## 7.1 TicketSummaryResponse

```json
{
  "ticketId": "01J...",
  "displayId": "INC-2048",
  "title": "Cannot sign in to Housing Portal",
  "applicationCode": "HOUSING_PORTAL",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "status": "WAITING_FOR_APPROVAL",
  "assignedTeam": "IDENTITY_SUPPORT",
  "createdAt": "2026-07-23T16:30:00Z",
  "updatedAt": "2026-07-23T16:42:00Z",
  "version": 7
}
```

## 7.2 TicketDetailResponse

```json
{
  "ticketId": "01J...",
  "displayId": "INC-2048",
  "requester": {
    "requesterId": "user-123",
    "displayName": "Aaron"
  },
  "title": "Cannot sign in to Housing Portal",
  "initialDescription": "Duo keeps asking me to enroll again.",
  "source": "PORTAL",
  "applicationCode": "HOUSING_PORTAL",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "status": "WAITING_FOR_APPROVAL",
  "assignment": {
    "teamId": "IDENTITY_SUPPORT",
    "supportUserId": null
  },
  "activeWorkflowId": "wf-7788",
  "pendingAction": {
    "actionId": "act-200",
    "actionType": "RESET_DUO_ENROLLMENT",
    "riskLevel": "MEDIUM",
    "approvalId": "apr-900",
    "expiresAt": "2026-07-23T18:00:00Z"
  },
  "resolution": null,
  "sla": {
    "status": "ACTIVE",
    "responseDueAt": "2026-07-23T17:00:00Z",
    "resolutionDueAt": "2026-07-24T00:30:00Z"
  },
  "createdAt": "2026-07-23T16:30:00Z",
  "updatedAt": "2026-07-23T16:42:00Z",
  "resolvedAt": null,
  "closedAt": null,
  "version": 7,
  "_links": {
    "messages": "/api/v1/tickets/01J.../messages",
    "timeline": "/api/v1/tickets/01J.../timeline"
  }
}
```

Employee responses exclude internal messages, prompts, policy internals, credentials, complete logs, and internal risk reasoning.

Support may view internal summaries but never secrets, tokens, or credentials.

---

# 8. Pagination

List endpoints use cursor pagination:

```http
GET /api/v1/tickets?limit=20&cursor=eyJ...
```

```json
{
  "items": [],
  "page": {
    "limit": 20,
    "nextCursor": "eyJ...",
    "hasMore": true
  }
}
```

Rules:

```text
default limit = 20
maximum limit = 100
stable sort = createdAt DESC, ticketId DESC
```

---

# 9. Public Employee APIs

## API-001 Create Ticket

Mapping:

```text
UC-01
SM-001
```

Endpoint:

```http
POST /api/v1/tickets
```

Headers:

```http
Authorization
Idempotency-Key
Content-Type
```

Request:

```json
{
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL"
}
```

For an employee request, `requesterId` comes from the JWT.

Validation:

| Field | Rule |
|---|---|
| title | required, 1–200 |
| description | required, 1–10000 |
| applicationCode | enum |
| source | PORTAL for employee MVP |

Success:

```http
201 Created
Location: /api/v1/tickets/{ticketId}
ETag: "0"
```

```json
{
  "ticketId": "01J...",
  "displayId": "INC-2048",
  "status": "NEW",
  "createdAt": "2026-07-23T16:30:00Z",
  "version": 0
}
```

## API-002 Get Ticket

```http
GET /api/v1/tickets/{ticketId}
```

Returns `TicketDetailResponse` and an ETag.

An unauthorized employee may receive 404 to prevent resource enumeration.

## API-003 List My Tickets

```http
GET /api/v1/tickets
```

Query parameters:

```text
status[]
applicationCode
limit
cursor
```

Requester identity comes from the JWT and cannot be supplied as a query parameter.

## API-004 Add Ticket Message

Mapping:

```text
UC-05
UC-09
SM-005 / SM-006 when resumed
```

Endpoint:

```http
POST /api/v1/tickets/{ticketId}/messages
```

Headers:

```http
Idempotency-Key
If-Match
```

Request:

```json
{
  "body": "My phone was replaced yesterday.",
  "attachmentIds": [
    "att-100"
  ],
  "replyToMessageId": "msg-20",
  "userRequestId": "req-88"
}
```

Employees cannot set author, visibility, or message type.

The service derives:

```text
author = authenticated requester
visibility = REQUESTER_VISIBLE
type = USER_MESSAGE
```

Success:

```http
201 Created
ETag: "8"
```

```json
{
  "messageId": "msg-21",
  "ticketId": "01J...",
  "createdAt": "2026-07-23T16:45:00Z",
  "ticketStatus": "INVESTIGATING",
  "ticketVersion": 8,
  "workflowResumeRequested": true
}
```

## API-005 List Ticket Messages

```http
GET /api/v1/tickets/{ticketId}/messages
```

Employee responses automatically filter internal messages.

## API-006 Cancel Ticket

Mapping:

```text
UC-26
SM-026
```

Endpoint:

```http
POST /api/v1/tickets/{ticketId}/cancel
```

Headers:

```http
Idempotency-Key
If-Match
```

Request:

```json
{
  "reasonCode": "NO_LONGER_NEEDED",
  "comment": "I resolved it myself."
}
```

Allowed source states:

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
FAILED
ESCALATED
```

EXECUTING and VERIFYING cannot be cancelled directly.

## API-007 Reopen Ticket

Mapping:

```text
UC-25
SM-024 / SM-025
```

Endpoint:

```http
POST /api/v1/tickets/{ticketId}/reopen
```

Request:

```json
{
  "reasonCode": "ISSUE_RECURRED",
  "comment": "The same Duo prompt returned this morning."
}
```

The service creates the new WorkflowId.

Success:

```http
202 Accepted
```

```json
{
  "ticketId": "01J...",
  "status": "INVESTIGATING",
  "resolutionCycleId": "cycle-2",
  "workflowProvisioningStatus": "REQUESTED",
  "version": 12
}
```

## API-008 Confirm Resolution

Mapping:

```text
UC-23
SM-022
```

Endpoint:

```http
POST /api/v1/tickets/{ticketId}/confirm-resolution
```

Only valid from RESOLVED.

## API-009 Get Ticket Timeline

Mapping:

```text
UC-30
```

Endpoint:

```http
GET /api/v1/tickets/{ticketId}/timeline
```

Returns a role-filtered cursor-based timeline.

---

# 10. Support and Administrator APIs

## API-010 List Support Queue Tickets

```http
GET /api/v1/support/tickets
```

Scope:

```text
tickets:queue:read
```

Filters:

```text
queueId
status[]
priority[]
assigneeId
applicationCode
createdAfter
createdBefore
limit
cursor
sort
```

Allowed sort fields:

```text
createdAt
updatedAt
priority
slaResolutionDueAt
```

## API-011 Add Support Message

```http
POST /api/v1/support/tickets/{ticketId}/messages
```

Request:

```json
{
  "body": "Internal note: Duo enrollment appears expired.",
  "visibility": "INTERNAL_SUPPORT_ONLY",
  "type": "SUPPORT_MESSAGE",
  "attachmentIds": []
}
```

Support may choose requester-visible or internal-support visibility.

## API-012 Request User Input

Mapping:

```text
UC-08
SM-004 / SM-007 / SM-031
```

Endpoint:

```http
POST /api/v1/support/tickets/{ticketId}/request-user-input
```

Request:

```json
{
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "message": "Please confirm whether you replaced your phone recently.",
  "resumeStatus": "INVESTIGATING"
}
```

Allowed resume states:

```text
TRIAGING
INVESTIGATING
```

## API-013 Assign Ticket

```http
POST /api/v1/support/tickets/{ticketId}/assign
```

Scope:

```text
tickets:assign
```

Request:

```json
{
  "teamId": "IDENTITY_SUPPORT",
  "supportUserId": "support-42"
}
```

## API-014 Escalate Ticket

```http
POST /api/v1/support/tickets/{ticketId}/escalate
```

Request:

```json
{
  "targetType": "TEAM",
  "targetId": "SECURITY_SUPPORT",
  "reasonCode": "UNKNOWN_EXTERNAL_SIDE_EFFECT",
  "comment": "Tool result could not be confirmed."
}
```

## API-015 Retry Failed Automation

```http
POST /api/v1/support/tickets/{ticketId}/retry-automation
```

Request:

```json
{
  "reasonCode": "TRANSIENT_DEPENDENCY_RECOVERED"
}
```

The client cannot choose an arbitrary workflow ID.

## API-016 Support Close Ticket

```http
POST /api/v1/support/tickets/{ticketId}/close
```

The Ticket must already be RESOLVED. This endpoint never bypasses verification.

---

# 11. Internal Service APIs

Internal APIs use OAuth 2.0 client credentials and service-specific scopes. A production-ready design may also use mTLS.

## API-017 Start Triage

Mapping:

```text
UC-06
SM-002
```

Endpoint:

```http
POST /internal/v1/tickets/{ticketId}/triage/start
```

Scope:

```text
tickets:triage:start
```

Request:

```json
{
  "workflowId": "wf-7788"
}
```

## API-018 Complete Classification

Mapping:

```text
UC-07
SM-003
```

Endpoint:

```http
POST /internal/v1/tickets/{ticketId}/classification
```

Scope:

```text
tickets:classify
```

Request:

```json
{
  "workflowId": "wf-7788",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "confidence": 0.94,
  "source": "TRIAGE_AGENT",
  "reasoningSummary": "Symptoms indicate MFA enrollment failure."
}
```

The reasoning summary must be redacted.

## API-019 Associate Active Workflow

```http
PUT /internal/v1/tickets/{ticketId}/active-workflow
```

Scope:

```text
tickets:workflow:associate
```

Request:

```json
{
  "workflowId": "wf-7788",
  "reasonCode": "INITIAL_INVESTIGATION"
}
```

It cannot overwrite a different active workflow.

## API-020 Get Internal Ticket Context

```http
GET /internal/v1/tickets/{ticketId}/context
```

Scope:

```text
tickets:context:read
```

Response:

```json
{
  "ticketId": "01J...",
  "displayId": "INC-2048",
  "applicationCode": "HOUSING_PORTAL",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "status": "INVESTIGATING",
  "activeWorkflowId": "wf-7788",
  "requester": {
    "requesterIdHash": "sha256:...",
    "locale": "en-US"
  },
  "latestRequesterVisibleMessages": [
    {
      "messageId": "msg-21",
      "body": "My phone was replaced yesterday.",
      "createdAt": "2026-07-23T16:45:00Z"
    }
  ],
  "version": 3
}
```

Only minimum required context is returned.

## API-021 Request User Input Internally

```http
POST /internal/v1/tickets/{ticketId}/user-input-requests
```

Scope:

```text
tickets:user-input:request
```

Request:

```json
{
  "workflowId": "wf-7788",
  "requestId": "req-88",
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "message": "Please confirm whether your phone was replaced.",
  "resumeStatus": "INVESTIGATING"
}
```

## API-022 Start Verification

Mapping:

```text
UC-19
SM-010
```

Endpoint:

```http
POST /internal/v1/tickets/{ticketId}/verifications
```

Scope:

```text
tickets:verification:start
```

Request:

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-300",
  "resolutionAttemptId": "attempt-2",
  "resolutionCandidate": {
    "resolutionCode": "USER_GUIDANCE_SUCCESSFUL",
    "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
    "summary": "User re-enrolled Duo on the replacement phone."
  }
}
```

Success:

```http
202 Accepted
```

---

# 12. Internal APIs Explicitly Not Exposed

```http
POST /internal/v1/tickets/{ticketId}/approval-granted
POST /internal/v1/tickets/{ticketId}/tool-result
POST /internal/v1/tickets/{ticketId}/verification-result
POST /internal/v1/tickets/{ticketId}/status
```

Approval, Tool Result, and Verification Result use event-driven integration. Generic status mutation is forbidden.

---

# 13. Authorization Matrix

| API | Employee | Support | Admin | Manager | Auditor | Service |
|---|---:|---:|---:|---:|---:|---:|
| Create | Own | Yes | Yes | No | No | Authorized |
| Get | Own | Queue | Scope | Scope | Read | Internal |
| List My Tickets | Own | No | No | No | No | No |
| Add Message | Own | Queue | Scope | No | Read-only | Authorized |
| Cancel | Own | Queue | Scope | No | No | No |
| Reopen | Own | Queue | Scope | No | No | No |
| Confirm | Own | Queue | Scope | No | No | No |
| Timeline | Filtered | Full queue | Scope | Scope | Read | Internal |
| Assign | No | Authorized | Yes | Yes | No | No |
| Escalate | No | Authorized | Yes | Yes | No | Policy Service |
| Retry | No | Authorized | Yes | No | No | Retry Service |
| Internal Triage | No | No | No | No | No | Scoped Service |

---

# 14. Rate Limits

| API | Recommended Limit |
|---|---|
| Create Ticket | 10/min/user |
| Add Message | 30/min/user |
| Get and List | 120/min/user |
| Cancel, Reopen, Confirm | 10/min/user |
| Internal Command | 300/min/client |

Rate limiting returns:

```http
429 Too Many Requests
Retry-After: 30
```

---

# 15. Validation Errors

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed.",
    "traceId": "trace-abc",
    "details": {
      "fieldErrors": [
        {
          "field": "title",
          "code": "SIZE",
          "message": "Title must contain between 1 and 200 characters."
        }
      ]
    }
  }
}
```

Never expose Java class names, stack traces, SQL, internal hostnames, secrets, or raw JWTs.

---

# 16. API Compatibility

Allowed within v1:

- New optional response fields
- New optional query parameters
- New error codes
- New enum values only after client tolerance review

Requires a new major version:

- Removing or renaming fields
- Changing field semantics
- Making optional fields required
- Changing ID formats
- Changing status-code semantics
- Introducing incompatible authorization behavior

---

# 17. OpenAPI File and CI

Recommended location:

```text
packages/api-contracts/ticket/openapi.yaml
```

CI validates:

- OpenAPI 3.1 schema
- Breaking changes
- Examples
- Security schemes
- Error envelope
- Unique operation IDs
- Controller conformance

Recommended operation IDs:

```text
createTicket
getTicket
listMyTickets
addTicketMessage
listTicketMessages
cancelTicket
reopenTicket
confirmTicketResolution
getTicketTimeline
listSupportTickets
addSupportTicketMessage
requestTicketUserInput
assignTicket
escalateTicket
retryTicketAutomation
closeTicketAsSupport
startTicketTriage
completeTicketClassification
associateTicketWorkflow
getInternalTicketContext
requestTicketUserInputInternally
startTicketVerification
```

---

# 18. API to Use Case Mapping

| API | Use Case |
|---|---|
| API-001 | UC-01 |
| API-002 | UC-02 |
| API-003 | UC-03 |
| API-004 | UC-05 / UC-09 |
| API-005 | UC-05 query support |
| API-006 | UC-26 |
| API-007 | UC-25 |
| API-008 | UC-23 |
| API-009 | UC-30 |
| API-010 | UC-04 |
| API-011 | UC-05 |
| API-012 | UC-08 |
| API-013 | UC-28 |
| API-014 | UC-27 |
| API-015 | UC-29 |
| API-016 | UC-23 |
| API-017 | UC-06 |
| API-018 | UC-07 |
| API-019 | UC-10 |
| API-020 | UC-02 internal |
| API-021 | UC-08 |
| API-022 | UC-19 |

---

# 19. Contract Testing

Every API covers:

- Valid request
- Missing token
- Forbidden actor
- Invalid schema
- Resource not found
- Invalid state
- Idempotent replay
- Same key with different payload
- If-Match success
- If-Match conflict
- Correct ETag
- Error envelope
- PII redaction
- OpenAPI conformance

Critical tests:

```text
employeeCannotReadAnotherUsersTicket
employeeCannotSendInternalSupportMessage
cancelRequiresIfMatch
reopenRejectsExpiredWindow
genericStatusMutationEndpointDoesNotExist
internalApiRejectsUserToken
sameIdempotencyKeyReturnsSameTicket
sameIdempotencyKeyWithDifferentPayloadFails
toolAndApprovalResultHttpEndpointsDoNotExist
```

---

# 20. Observability

Every API server span records:

```text
http.request.method
http.route
http.response.status_code
opsmind.use_case_id
opsmind.ticket_status
opsmind.actor_type
error.type
```

The following are trace or log fields, not Prometheus labels:

```text
ticket_id
workflow_id
requester_id_hash
idempotency_key_hash
```

Never log raw Authorization headers, idempotency keys, message bodies, or Ticket descriptions.

---

# 21. Acceptance Criteria

- [x] Public APIs defined
- [x] Support and administrator APIs defined
- [x] Internal APIs defined
- [x] APIs mapped to use cases
- [x] Generic status mutation rejected
- [x] Authentication and authorization defined
- [x] Idempotency-Key defined
- [x] If-Match and ETag defined
- [x] Pagination defined
- [x] Error envelope defined
- [x] PII visibility defined
- [x] OpenAPI compatibility defined
- [x] Contract tests defined

---

# 22. Next Step

Create:

```text
06-event-contracts/README_CN.md
06-event-contracts/README_EN.md
```

The event contracts will define the versioned JSON schemas published and consumed by Ticket Workflow.
