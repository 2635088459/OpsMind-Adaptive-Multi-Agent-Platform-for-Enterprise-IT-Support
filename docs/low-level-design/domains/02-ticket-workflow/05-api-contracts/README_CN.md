# OpsMind Ticket Workflow — 05 API Contracts

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level REST API Contract  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `04-use-cases/README_CN.md`  
> **标准：** REST + OpenAPI 3.1  
> **建议路径：** `docs/low-level-design/domains/02-ticket-workflow/05-api-contracts/README_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow 的同步 HTTP API 契约，包括：

- Public Employee API
- Support / Admin API
- Internal Service API
- Request / Response Schema
- Authentication 与 Authorization
- Idempotency
- Optimistic Concurrency
- Pagination
- Error Envelope
- PII Visibility
- API 与 `UC-xx`、`SM-xxx` 的映射

本文档可以直接作为后续 OpenAPI 3.1 YAML 的设计输入。

---

# 2. 核心 API 原则

## 2.1 每个业务 API 必须映射到 Use Case

禁止提供通用状态修改接口：

```http
POST /api/v1/tickets/{ticketId}/change-status
POST /internal/v1/tickets/{ticketId}/transitions
```

原因：

- Caller 不得任意指定目标状态。
- 通用接口会绕过 Guard、Business Invariant 和 State Machine。
- 每个状态变化必须通过有明确业务语义的 Command。

推荐：

```http
POST /api/v1/tickets/{ticketId}/cancel
POST /api/v1/tickets/{ticketId}/reopen
POST /internal/v1/tickets/{ticketId}/triage/start
```

## 2.2 异步结果使用 Event

以下结果主要通过 RabbitMQ Event 进入 Ticket Workflow：

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

不会为这些事件开放公共 HTTP 状态回调。

## 2.3 API 不直接暴露 Domain Entity

API 使用 DTO，禁止直接序列化：

```text
Ticket Aggregate
JPA Entity
Domain Event
```

## 2.4 API Version 与 Event Version 分离

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

本地开发：

```text
http://localhost:8080/api/v1
http://localhost:8080/internal/v1
```

---

# 4. 通用 Headers

## 4.1 Authorization

```http
Authorization: Bearer <JWT>
```

- Public / Support API：Keycloak User Access Token
- Internal API：OAuth 2.0 Client Credentials Token

## 4.2 Trace 与 Correlation

```http
traceparent: 00-<trace-id>-<span-id>-01
X-Correlation-Id: <correlation-id>
```

如果 `X-Correlation-Id` 缺失，服务生成一个。

## 4.3 Idempotency

需要幂等保护的 Command 使用：

```http
Idempotency-Key: <unique-key>
```

规则：

- 长度 1–128
- 在 Actor / Client 范围内唯一
- 相同 Key + 相同 Payload 返回第一次结果
- 相同 Key + 不同 Payload 返回 `IDEMPOTENCY_KEY_REUSED`

## 4.4 Optimistic Concurrency

修改已有 Ticket 时：

```http
If-Match: "7"
```

响应：

```http
ETag: "8"
```

Version 不一致：

```http
412 Precondition Failed
```

```text
CONCURRENT_UPDATE
```

## 4.5 Content Type

```http
Content-Type: application/json
Accept: application/json
```

---

# 5. ID、时间与枚举

## 时间

```text
ISO 8601 UTC
2026-07-23T16:30:00Z
```

## Internal ID

```text
UUID 或 ULID
```

## Display ID

```text
INC-2048
```

API Path 使用内部 `ticketId`，UI 显示 `displayId`。

---

# 6. 通用 Error Envelope

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

## 状态码

| HTTP | 含义 |
|---|---|
| 200 | Query 或 Command 成功 |
| 201 | Resource 创建成功 |
| 202 | Command 已接受，后续异步执行 |
| 204 | 成功，无 Response Body |
| 400 | Request Schema 错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | Resource 不存在或不可见 |
| 409 | 业务冲突 |
| 412 | `If-Match` Version 冲突 |
| 422 | 业务语义不合法 |
| 429 | Rate Limit |
| 500 | 未知内部错误 |
| 503 | 暂时不可用 |

## 主要 Error Codes

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

# 7. 通用 Response Schemas

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

## 7.3 Visibility

Employee 不可看到：

- Internal Support Message
- Agent Prompt
- Policy 内部规则
- Credential
- 完整内部 Log
- 内部 Risk Reasoning

Support 可查看内部 Summary，但仍不能查看 Secret、Token 或 Credential。

---

# 8. Pagination

List API 使用 Cursor Pagination：

```http
GET /api/v1/tickets?limit=20&cursor=eyJ...
```

Response：

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

规则：

```text
default limit = 20
maximum limit = 100
stable sort = createdAt DESC, ticketId DESC
```

---

# 9. Public Employee APIs

## API-001 Create Ticket

### 映射

```text
UC-01
SM-001
```

### Endpoint

```http
POST /api/v1/tickets
```

### Headers

```http
Authorization
Idempotency-Key
Content-Type
```

### Request

```json
{
  "title": "Cannot sign in to Housing Portal",
  "description": "Duo keeps asking me to enroll again.",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL"
}
```

普通 Employee 的 `requesterId` 必须从 JWT 获取。

### Validation

| Field | Rule |
|---|---|
| title | required, 1–200 |
| description | required, 1–10000 |
| applicationCode | enum |
| source | Employee MVP 仅 PORTAL |

### Success

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

### Errors

```text
400 VALIDATION_ERROR
401 UNAUTHORIZED
403 FORBIDDEN
409 IDEMPOTENCY_KEY_REUSED
429 RATE_LIMITED
```

---

## API-002 Get Ticket

### 映射

```text
UC-02
```

### Endpoint

```http
GET /api/v1/tickets/{ticketId}
```

### Success

```http
200 OK
ETag: "7"
```

Response：

```text
TicketDetailResponse
```

### Authorization

- Requester 自己的 Ticket；或
- Support / Admin / Manager / Auditor 有相应 Scope。

未授权 Employee 可统一返回 404，避免 Ticket 枚举。

---

## API-003 List My Tickets

### 映射

```text
UC-03
```

### Endpoint

```http
GET /api/v1/tickets
```

### Query Parameters

| Name | Type | Required |
|---|---|---:|
| status | string[] | no |
| applicationCode | string | no |
| limit | integer | no |
| cursor | string | no |

RequesterId 从 JWT 获取，不能由 Query Parameter 指定。

---

## API-004 Add Ticket Message

### 映射

```text
UC-05
UC-09
SM-005 / SM-006 when resumed
```

### Endpoint

```http
POST /api/v1/tickets/{ticketId}/messages
```

### Headers

```http
Idempotency-Key
If-Match
```

### Request

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

普通 Employee 不能指定：

```text
author
visibility
messageType
```

服务自动设置：

```text
author = authenticated requester
visibility = REQUESTER_VISIBLE
type = USER_MESSAGE
```

### Success

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

### Errors

```text
403 FORBIDDEN
404 TICKET_NOT_FOUND
409 IDEMPOTENCY_KEY_REUSED
412 CONCURRENT_UPDATE
422 INVALID_TICKET_STATE
```

---

## API-005 List Ticket Messages

### Endpoint

```http
GET /api/v1/tickets/{ticketId}/messages
```

### Query

```text
limit
cursor
```

Employee Response 自动过滤 Internal Message。

---

## API-006 Cancel Ticket

### 映射

```text
UC-26
SM-026
```

### Endpoint

```http
POST /api/v1/tickets/{ticketId}/cancel
```

### Headers

```http
Idempotency-Key
If-Match
```

### Request

```json
{
  "reasonCode": "NO_LONGER_NEEDED",
  "comment": "I resolved it myself."
}
```

### Allowed States

```text
NEW
TRIAGING
INVESTIGATING
WAITING_FOR_USER
WAITING_FOR_APPROVAL
FAILED
ESCALATED
```

### Success

```json
{
  "ticketId": "01J...",
  "status": "CANCELLED",
  "cancelledAt": "2026-07-23T17:00:00Z",
  "version": 5
}
```

`EXECUTING` 或 `VERIFYING` 不能直接 Cancel。

---

## API-007 Reopen Ticket

### 映射

```text
UC-25
SM-024 / SM-025
```

### Endpoint

```http
POST /api/v1/tickets/{ticketId}/reopen
```

### Headers

```http
Idempotency-Key
If-Match
```

### Request

```json
{
  "reasonCode": "ISSUE_RECURRED",
  "comment": "The same Duo prompt returned this morning."
}
```

`newWorkflowId` 由服务协作生成，用户不能指定。

### Success

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

### Errors

```text
422 REOPEN_NOT_ALLOWED
422 REOPEN_WINDOW_EXPIRED
412 CONCURRENT_UPDATE
```

---

## API-008 Confirm Resolution

### 映射

```text
UC-23
SM-022
```

### Endpoint

```http
POST /api/v1/tickets/{ticketId}/confirm-resolution
```

### Headers

```http
Idempotency-Key
If-Match
```

### Request

```json
{
  "comment": "I can sign in now."
}
```

### Success

```json
{
  "ticketId": "01J...",
  "status": "CLOSED",
  "closeReason": "REQUESTER_CONFIRMED",
  "closedAt": "2026-07-23T18:00:00Z",
  "version": 11
}
```

仅允许从 `RESOLVED` 执行。

---

## API-009 Get Ticket Timeline

### 映射

```text
UC-30
```

### Endpoint

```http
GET /api/v1/tickets/{ticketId}/timeline
```

Response：

```json
{
  "items": [
    {
      "timelineEntryId": "tl-1",
      "type": "STATUS_CHANGED",
      "occurredAt": "2026-07-23T16:30:00Z",
      "summary": "Ticket created",
      "visibility": "REQUESTER_VISIBLE",
      "data": {
        "fromStatus": null,
        "toStatus": "NEW"
      }
    }
  ],
  "page": {
    "limit": 20,
    "nextCursor": null,
    "hasMore": false
  }
}
```

---

# 10. Support / Admin APIs

## API-010 List Support Queue Tickets

### 映射

```text
UC-04
```

### Endpoint

```http
GET /api/v1/support/tickets
```

### Scope

```text
tickets:queue:read
```

### Filters

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

允许 Sort：

```text
createdAt
updatedAt
priority
slaResolutionDueAt
```

---

## API-011 Add Support Message

### 映射

```text
UC-05
```

### Endpoint

```http
POST /api/v1/support/tickets/{ticketId}/messages
```

### Request

```json
{
  "body": "Internal note: Duo enrollment appears expired.",
  "visibility": "INTERNAL_SUPPORT_ONLY",
  "type": "SUPPORT_MESSAGE",
  "attachmentIds": []
}
```

Support 可指定：

```text
REQUESTER_VISIBLE
INTERNAL_SUPPORT_ONLY
```

---

## API-012 Request User Input

### 映射

```text
UC-08
SM-004 / SM-007 / SM-031
```

### Endpoint

```http
POST /api/v1/support/tickets/{ticketId}/request-user-input
```

### Request

```json
{
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "message": "Please confirm whether you replaced your phone recently.",
  "resumeStatus": "INVESTIGATING"
}
```

`resumeStatus` 仅允许：

```text
TRIAGING
INVESTIGATING
```

### Success

```json
{
  "ticketId": "01J...",
  "status": "WAITING_FOR_USER",
  "userRequestId": "req-88",
  "slaStatus": "PAUSED",
  "version": 6
}
```

---

## API-013 Assign Ticket

### 映射

```text
UC-28
```

### Endpoint

```http
POST /api/v1/support/tickets/{ticketId}/assign
```

### Scope

```text
tickets:assign
```

### Request

```json
{
  "teamId": "IDENTITY_SUPPORT",
  "supportUserId": "support-42"
}
```

---

## API-014 Escalate Ticket

### 映射

```text
UC-27
SM-033 / SM-034
```

### Endpoint

```http
POST /api/v1/support/tickets/{ticketId}/escalate
```

### Request

```json
{
  "targetType": "TEAM",
  "targetId": "SECURITY_SUPPORT",
  "reasonCode": "UNKNOWN_EXTERNAL_SIDE_EFFECT",
  "comment": "Tool result could not be confirmed."
}
```

---

## API-015 Retry Failed Automation

### 映射

```text
UC-29
SM-028
```

### Endpoint

```http
POST /api/v1/support/tickets/{ticketId}/retry-automation
```

### Request

```json
{
  "reasonCode": "TRANSIENT_DEPENDENCY_RECOVERED"
}
```

WorkflowId 由服务生成或恢复，客户端不能任意指定。

### Success

```http
202 Accepted
```

### Errors

```text
422 INVALID_TICKET_STATE
422 RETRY_BUDGET_EXHAUSTED
```

---

## API-016 Support Close Ticket

### 映射

```text
UC-23
SM-022
```

### Endpoint

```http
POST /api/v1/support/tickets/{ticketId}/close
```

### Request

```json
{
  "reasonCode": "SUPPORT_CONFIRMED",
  "comment": "Requester confirmed by phone."
}
```

必须满足：

- Ticket 当前为 `RESOLVED`
- Support 有 Queue Access
- 不能绕过 Verification

---

# 11. Internal Service APIs

Internal API 使用：

```text
OAuth 2.0 Client Credentials
service-specific scopes
```

生产级设计可以增加 mTLS。

## API-017 Start Triage

### 映射

```text
UC-06
SM-002
```

### Endpoint

```http
POST /internal/v1/tickets/{ticketId}/triage/start
```

### Scope

```text
tickets:triage:start
```

### Request

```json
{
  "workflowId": "wf-7788"
}
```

---

## API-018 Complete Classification

### 映射

```text
UC-07
SM-003
```

### Endpoint

```http
POST /internal/v1/tickets/{ticketId}/classification
```

### Scope

```text
tickets:classify
```

### Request

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

`reasoningSummary` 必须脱敏。

---

## API-019 Associate Active Workflow

### 映射

```text
UC-10
```

### Endpoint

```http
PUT /internal/v1/tickets/{ticketId}/active-workflow
```

### Scope

```text
tickets:workflow:associate
```

### Request

```json
{
  "workflowId": "wf-7788",
  "reasonCode": "INITIAL_INVESTIGATION"
}
```

不能覆盖不同的现有 Active Workflow。

---

## API-020 Get Internal Ticket Context

### 映射

```text
UC-02 internal variant
```

### Endpoint

```http
GET /internal/v1/tickets/{ticketId}/context
```

### Scope

```text
tickets:context:read
```

### Response

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

返回最小必要上下文，不返回 Credential、Token 或完整内部 Timeline。

---

## API-021 Request User Input Internally

### 映射

```text
UC-08
```

### Endpoint

```http
POST /internal/v1/tickets/{ticketId}/user-input-requests
```

### Scope

```text
tickets:user-input:request
```

### Request

```json
{
  "workflowId": "wf-7788",
  "requestId": "req-88",
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "message": "Please confirm whether your phone was replaced.",
  "resumeStatus": "INVESTIGATING"
}
```

---

## API-022 Start Verification

### 映射

```text
UC-19
SM-010
```

### Endpoint

```http
POST /internal/v1/tickets/{ticketId}/verifications
```

### Scope

```text
tickets:verification:start
```

### Request

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

### Success

```http
202 Accepted
```

```json
{
  "ticketId": "01J...",
  "status": "VERIFYING",
  "verificationId": "ver-300",
  "version": 6
}
```

---

# 12. 明确不提供的 Internal APIs

```http
POST /internal/v1/tickets/{ticketId}/approval-granted
POST /internal/v1/tickets/{ticketId}/tool-result
POST /internal/v1/tickets/{ticketId}/verification-result
POST /internal/v1/tickets/{ticketId}/status
```

原因：

- Approval、Tool Result 和 Verification Result 使用 Event-driven Integration。
- 通用 Status Endpoint 会绕过状态机。
- 同步回调如未来确有需要，必须新增 ADR。

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

| API | 建议限制 |
|---|---|
| Create Ticket | 10/min/user |
| Add Message | 30/min/user |
| Get / List | 120/min/user |
| Cancel / Reopen / Confirm | 10/min/user |
| Internal Command | 300/min/client |

返回：

```http
429 Too Many Requests
Retry-After: 30
```

---

# 15. Validation Error

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

禁止返回：

- Java Class Name
- Stack Trace
- SQL
- Internal Hostname
- Secret
- Raw JWT

---

# 16. API Compatibility

## 同一 v1 允许

- 增加 Optional Response Field
- 增加 Optional Query Parameter
- 增加新 Error Code
- 增加枚举值前需确认客户端容错

## 必须升级 Major Version

- 删除或重命名字段
- 改变字段语义
- Optional 改为 Required
- 改变 ID 格式
- 改变状态码语义
- 引入不兼容授权变化

---

# 17. OpenAPI 文件与 CI

建议位置：

```text
packages/api-contracts/ticket/openapi.yaml
```

CI 验证：

- OpenAPI 3.1 Schema
- Breaking Change
- Examples
- Security Scheme
- Error Envelope
- OperationId 唯一
- Controller 与 Contract 一致

推荐 OperationId：

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

# 18. API 到 Use Case 映射

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

# 19. Contract Test 要求

每个 API 至少测试：

- Valid Request
- Missing Token
- Forbidden Actor
- Invalid Schema
- Resource Not Found
- Invalid State
- Idempotent Replay
- 同 Key 不同 Payload
- If-Match Success
- If-Match Conflict
- ETag
- Error Envelope
- PII Redaction
- OpenAPI Conformance

关键测试：

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

每个 API Server Span 记录：

```text
http.request.method
http.route
http.response.status_code
opsmind.use_case_id
opsmind.ticket_status
opsmind.actor_type
error.type
```

以下只作为 Trace / Log Field，不作为 Prometheus Label：

```text
ticket_id
workflow_id
requester_id_hash
idempotency_key_hash
```

禁止记录原始：

```text
Authorization
Idempotency-Key
Message Body
Ticket Description
```

---

# 21. 验收标准

- [x] Public API 已定义。
- [x] Support / Admin API 已定义。
- [x] Internal API 已定义。
- [x] API 与 Use Case 已映射。
- [x] Generic Status Mutation API 已拒绝。
- [x] Authentication 与 Authorization 已定义。
- [x] Idempotency-Key 已定义。
- [x] If-Match / ETag 已定义。
- [x] Pagination 已定义。
- [x] Error Envelope 已定义。
- [x] PII Visibility 已定义。
- [x] OpenAPI Compatibility 已定义。
- [x] Contract Test 已定义。

---

# 22. 下一步

下一份文档：

```text
06-event-contracts/README_CN.md
06-event-contracts/README_EN.md
```

Event Contract 将定义 Ticket Workflow 发布和消费的 Versioned JSON Schema。
