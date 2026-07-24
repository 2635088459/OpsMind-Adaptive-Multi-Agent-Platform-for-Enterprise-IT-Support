# OpsMind Ticket Workflow — 11 Security and Authorization

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Security and Authorization Design  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **身份平台：** Keycloak  
> **授权模型：** RBAC + Resource Ownership + Queue Scope + Service Scope + Policy Guard  
> **依赖：** `01-domain-model_CN.md` 至 `10-error-handling-and-reconciliation_CN.md`  
> **建议路径：** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/11-security-and-authorization_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow 的身份认证、授权、跨服务信任、数据可见性、安全审计和威胁防护。

核心目标：

```text
每个请求和事件必须具有可信身份。
每个操作必须同时满足 Role、Scope、资源归属、Queue 范围和状态机。
任何服务都不能通过 Ticket API 绕过 Approval、Policy 或 Verification。
任何 Secret 都不能进入 Ticket、Event、Log、Trace、Metric 或 LangSmith。
高风险恢复必须经过强认证、审批、审计和独立验证。
```

---

# 2. Trust Boundaries

```text
Browser / Frontend
    ↓ User JWT
API Gateway / Ticket API
    ↓ Application Authorization
Ticket Domain
    ↓ Transactional Outbox
RabbitMQ
    ↓ Service Identity + Event Validation
Agent Runtime / Approval / Tool Gateway / Verification
```

系统必须假设：

- 浏览器输入不可信。
- 合法 Token 仍可能 Scope 不足。
- Internal Service 可能配置错误或被盗用。
- Event 可能重复、迟到、乱序、伪造或引用错误。
- Support 账户可能越权访问。
- Agent 输出可能受到 Prompt Injection。
- 日志、Trace 和导出文件可能扩大数据泄漏范围。

---

# 3. Security Principles

## 3.1 Deny by Default

```text
No Role
No Scope
No Ownership
No Queue Access
No Valid Service Identity
No Valid Business Guard
→ DENY
```

## 3.2 Least Privilege

User、Support、Admin 和 Service 只拥有完成职责所需的最小权限。

## 3.3 Defense in Depth

安全控制存在于：

```text
API Gateway
JWT Validator
Endpoint Scope
Application Authorization
Domain Guard
Database Constraint
Event Consumer Validation
Audit
```

## 3.4 Authentication 不等于 Authorization

拥有合法 Token 不代表可以读取任意 Ticket。

## 3.5 Authorization 不等于业务合法

即使拥有 `tickets:close`，也不能绕过状态机从 `EXECUTING` 直接 `CLOSED`。

---

# 4. Keycloak Design

## 4.1 Realm

建议按环境隔离：

```text
opsmind-local
opsmind-ci
opsmind-demo
opsmind-staging
opsmind-prod
```

生产和非生产环境不得共享：

- Signing Key
- Client Secret
- Service Account
- User Session
- RabbitMQ Credential

## 4.2 Clients

```text
opsmind-web
ticket-workflow-service
agent-runtime-service
policy-approval-service
tool-gateway-service
verification-service
notification-service
evaluation-service
support-operations-service
```

## 4.3 Client Types

### Web

```text
Public Client
Authorization Code Flow
PKCE S256
```

禁止：

```text
Implicit Flow
Resource Owner Password Grant
```

### Backend Service

```text
Confidential Client
Client Credentials
Service Account Enabled
```

生产增强可以增加：

```text
mTLS
Workload Identity
DPoP
```

---

# 5. JWT Validation

Ticket Service 必须验证：

```text
signature
issuer
audience
expiration
not-before
authorized party
token type
subject
required scope
environment
```

必须拒绝：

- `alg=none`
- 未知签名算法
- 错误 Issuer 或 Audience
- 过期 Token
- `nbf` 尚未生效
- User Token 调用 Service-only Internal API
- Service Token 调用 Employee Ownership API
- 缺少必要 Scope

JWKS 规则：

- 仅通过 HTTPS 获取。
- 缓存公钥。
- 支持 Key Rotation。
- 未知 `kid` 时仅刷新一次。
- 刷新失败时 Fail Closed。
- Clock Skew 建议 60 秒。

---

# 6. Principal Model

```text
PrincipalContext
├── principalType
├── subject
├── clientId
├── roles
├── scopes
├── queueMemberships
├── assignedTeams
├── authenticationLevel
├── tokenId
└── issuedAt
```

`principalType`：

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
SECURITY_ADMIN
SERVICE
SYSTEM_SCHEDULER
```

Principal Context 必须来自可信 Token 和授权数据，不能来自 Request Body。

---

# 7. Roles and Scopes

## 7.1 Realm Roles

```text
EMPLOYEE
IT_SUPPORT
IT_ADMIN
IT_MANAGER
AUDITOR
SECURITY_ADMIN
RECONCILIATION_OPERATOR
RECONCILIATION_APPROVER
```

不创建可以绕过所有安全边界的通用 `SUPER_ADMIN`。

## 7.2 Read Scopes

```text
tickets:own:read
tickets:queue:read
tickets:any:read
tickets:timeline:read
tickets:audit:read
tickets:context:read
```

## 7.3 Write Scopes

```text
tickets:create
tickets:message:write
tickets:message:internal
tickets:cancel
tickets:reopen
tickets:confirm
tickets:assign
tickets:escalate
tickets:automation:retry
tickets:close
```

## 7.4 Internal Workflow Scopes

```text
tickets:triage:start
tickets:classify
tickets:workflow:associate
tickets:user-input:request
tickets:verification:start
tickets:execution:consume
```

## 7.5 Recovery Scopes

```text
tickets:reconciliation:read
tickets:reconciliation:operate
tickets:reconciliation:approve
tickets:event:replay
tickets:event:correct
tickets:compensation:request
tickets:compensation:approve
```

---

# 8. Role-to-Scope Mapping

## EMPLOYEE

```text
tickets:create
tickets:own:read
tickets:message:write
tickets:cancel
tickets:reopen
tickets:confirm
tickets:timeline:read
```

仅限自己的 Ticket。

## IT_SUPPORT

```text
tickets:queue:read
tickets:message:write
tickets:message:internal
tickets:assign
tickets:escalate
tickets:close
tickets:user-input:request
tickets:timeline:read
```

受 Queue、Assignment 和 Temporary Grant 限制。

## IT_MANAGER

```text
tickets:queue:read
tickets:assign
tickets:escalate
tickets:close
tickets:reconciliation:read
```

## IT_ADMIN

```text
tickets:any:read
tickets:assign
tickets:escalate
tickets:automation:retry
tickets:reconciliation:operate
tickets:event:replay
```

Admin 仍不能绕过 Approval、Verification 或 State Machine。

## AUDITOR

```text
tickets:audit:read
tickets:timeline:read
tickets:reconciliation:read
```

只读。

---

# 9. Resource Ownership

Employee 操作必须满足：

```text
ticket.requesterId == principal.subject
```

适用于：

- Get Ticket
- List My Tickets
- Add User Message
- Cancel
- Reopen
- Confirm Resolution
- View Requester Timeline

Employee Request Body 不允许提交 `requesterId` 覆盖 Token Identity。

为防止资源枚举，Employee 请求他人 Ticket 时统一返回：

```text
404 TICKET_NOT_FOUND
```

---

# 10. Queue-based Authorization

Support 访问 Ticket 必须同时满足 Endpoint Scope，并满足至少一个条件：

```text
ticket.currentTeamId in principal.queueMemberships
OR ticket.currentSupportUserId == principal.subject
OR valid temporary cross-queue grant exists
OR principal has tickets:any:read
```

MVP 可从 Keycloak Group Claim 获取：

```json
{
  "support_queues": [
    "IDENTITY_SUPPORT",
    "DEVICE_SUPPORT"
  ]
}
```

如果 Queue 数量过多，使用 Authorization Service 和短期 Cache，避免 JWT 过大。

---

# 11. Cross-queue Access

跨 Queue 临时访问必须包含：

```text
grantId
operatorId
ticketId or queueId
reasonCode
grantedBy
expiresAt
auditReference
```

建议最长：

```text
8 hours
```

禁止：

- 永不过期的临时权限
- 仅通过 Slack 或 Email 口头授权
- 通过修改 Ticket Team 伪造访问
- 无审计的管理员绕过

---

# 12. Authorization Flow

```text
1. Validate JWT
2. Build PrincipalContext
3. Check Endpoint Scope
4. Load TicketAuthorizationProjection
5. Check Ownership / Queue / Assignment / Temporary Grant
6. Apply Field-level Visibility
7. Execute Use Case
8. Validate Domain State
9. Audit Sensitive Operation
```

授权投影仅需：

```text
ticketId
requesterId
currentTeamId
currentSupportUserId
status
dataClassification
```

---

# 13. Field-level Visibility

## EMPLOYEE

可见：

- 基本 Ticket 信息
- Requester-visible Message
- 当前状态
- 安全的 Approval / Processing 摘要
- Resolution Summary
- SLA 摘要

不可见：

- Internal Support Message
- Agent Prompt
- Internal Policy
- Tool Credential
- Recovery Audit
- Security Case
- 其他用户信息

## IT_SUPPORT

增加可见：

- Internal Note
- Assignment
- Approval Summary
- Tool Result Summary
- Verification Summary
- Escalation Summary

## AUDITOR

可见：

- Status History
- Actor / Event Reference
- Approval 和 Recovery Audit

默认 Message Body 脱敏。

## SERVICE

只返回完成当前任务需要的最小上下文。

---

# 14. Data Classification

```text
PUBLIC
INTERNAL
SENSITIVE
SECRET
```

示例：

| Data | Classification |
|---|---|
| Status / Category | INTERNAL |
| Ticket ID / Display ID | INTERNAL |
| Requester ID | SENSITIVE |
| Title / Description | SENSITIVE |
| Message Body | SENSITIVE |
| Resolution Summary | SENSITIVE |
| Password / Token / API Key | SECRET |

规则：

```text
SECRET 不允许进入 Ticket Domain。
```

---

# 15. PII Minimization

Event 和 Internal API 只传输最小必要字段。

推荐 Requester Pseudonymous ID：

```text
HMAC-SHA-256(service-controlled key, requesterId)
```

普通无 Salt 的 SHA-256 不足以保护低熵、可枚举 ID。

Hash 仍属于 Pseudonymous Sensitive Data，不能公开。

---

# 16. Secret Handling

禁止把 Secret 写入：

```text
Ticket Description
Ticket Message
Outbox Payload
Event Payload
Status History
Reconciliation Evidence Body
Application Log
OpenTelemetry
Prometheus
LangSmith
```

Secret 来源：

```text
Keycloak Client Secret
RabbitMQ Credential
Database Password
Tool API Token
Duo / Okta Admin Credential
Encryption Key
```

存储：

```text
Docker Secret for local/demo
Cloud Secret Manager for production
```

必须支持独立 Rotation。

---

# 17. Secret Detection and Redaction

扫描入口：

- Create Ticket
- Add Message
- Agent-generated Requester Message
- Event Payload
- Reconciliation Evidence
- Attachment Metadata

检测：

- Bearer Token
- Private Key Header
- API Key Prefix
- Password Assignment
- Authorization Header
- Session Cookie
- MFA Recovery Code

高置信度 Secret：

```text
Block or Quarantine
Fail Closed
Security Alert
```

告警不得包含原始 Secret。

---

# 18. Content and Prompt Safety

Agent 输出给用户的内容不得：

- 请求密码、MFA OTP、Recovery Code 或 Token
- 暴露 System Prompt
- 暴露内部 Policy
- 引导用户执行不安全命令
- 暴露其他用户数据

固定安全声明：

```text
OpsMind 永远不会要求用户通过 Ticket 提交密码、Access Token、Recovery Code 或 MFA OTP。
```

用户文本必须被视为 Data，不是 Agent System Instruction。

---

# 19. Internal Service Identity

每个服务使用独立：

```text
Keycloak Client
Service Account
Client Secret / Workload Identity
RabbitMQ Credential
```

禁止共享服务身份。

原因：

- 无法归因
- 无法最小权限
- 无法独立吊销
- Credential 泄漏影响扩大

---

# 20. Internal Service Scope Matrix

| Service | Allowed Scope |
|---|---|
| Agent Runtime | context read, triage start, classify, user-input request, verification start |
| Approval Service | Publish Approval / Policy Events; no generic Ticket write |
| Tool Gateway | Consume execution-ready; no Ticket status endpoint |
| Verification Service | Consume verification request; publish result |
| Notification Service | Limited notification context |
| Evaluation Service | Redacted closed-ticket outcome |
| Support Operations | Reconciliation scopes |
| Scheduler | Auto-close / SLA-specific scopes |

Internal Token 必须具有：

```text
aud = ticket-workflow-service
```

---

# 21. RabbitMQ Security

每个服务使用独立 RabbitMQ User。

建议环境隔离 Virtual Host：

```text
/opsmind-local
/opsmind-ci
/opsmind-demo
/opsmind-prod
```

权限示例：

## Ticket Workflow

```text
Publish: ticket.*
Consume: ticket-workflow.* queues
```

## Approval Service

```text
Publish: approval.*, policy.*
```

## Tool Gateway

```text
Consume: ticket.execution_ready.v1
Publish: tool.execution.*
```

## Verification Service

```text
Consume: ticket.verification_started.v1
Publish: verification.*
```

---

# 22. Event Trust Validation

Broker Authentication 不能证明 Event 业务合法。

Consumer 必须验证：

```text
producer
eventType
eventVersion
routingKey
ticketId
workflowId
resolutionCycleId
actionId
approvalId
toolExecutionId
verificationId
payload schema
dataClassification
```

Producer Allowlist 示例：

```text
approval.granted
→ policy-approval-service only
```

```text
tool.execution.completed
→ tool-gateway-service only
```

错误 Producer：

```text
EVENT_PRODUCER_NOT_ALLOWED
→ DLQ
→ Security Alert
```

生产增强可以增加 JWS Event Signature，但签名不能替代业务引用校验。

---

# 23. Approval Trust Boundary

Approval Service 独占：

```text
approvalId
decision
approver
expiresAt
risk policy
```

Ticket Service 在进入 EXECUTING 前匹配：

```text
ticketId
workflowId
actionId
actionType
approvalId
riskLevel
expiresAt
```

Approval 必须满足：

```text
approvedAt <= expiresAt
```

且不能跨 Ticket、Workflow、Action 或 Risk Context 复用。

---

# 24. Tool Trust Boundary

Ticket Service 不获取 Tool Credential。

Ticket 只发布：

```text
actionId
actionType
approvalId / policyDecisionId
toolExecutionId
business idempotency key
```

Tool Gateway 必须重新验证：

```text
action allowlisted
policy decision matches action
target scope matches Ticket context
execution identity unique
credential acquired internally
```

这用于防止 Confused Deputy Attack。

---

# 25. Verification Trust Boundary

Verification Service 不直接修改 Ticket。

Ticket Service 只接受来自允许 Producer 的 `verification.completed`，并验证：

```text
verificationId
workflowId
resolutionAttemptId
resolutionCycleId
evidence summary
terminal-result conflict
```

只有可信且属于当前处理周期的 Verification Success 可以 Resolve。

---

# 26. High-risk Operations

高风险操作包括：

- Permission Change
- MFA Reset
- Account Disable
- Device Wipe
- Credential Rotation
- Compensation
- Correction Event
- Cross-queue Access
- Data Repair

要求：

```text
Strong Authentication
Explicit Scope
Reason Code
Approval
Audit
Verification
```

---

# 27. Step-up Authentication

以下操作建议要求：

```text
MFA-authenticated session
authentication age < 15 minutes
```

适用：

- Compensation Approval
- Event Correction
- Security Reconciliation
- Data Repair
- Cross-queue Temporary Grant
- Bulk Operation

---

# 28. Separation of Duties

同一人不能无条件完成：

```text
Propose Recovery
Approve Recovery
Verify Recovery
```

MVP 使用 Four-eyes：

- Operator 提议
- Approver 批准

生产增强可要求 Independent Verifier。

---

# 29. Reconciliation Authorization

```text
tickets:reconciliation:read
tickets:reconciliation:operate
tickets:reconciliation:approve
tickets:event:replay
tickets:event:correct
tickets:compensation:request
tickets:compensation:approve
```

Correction Event 需要：

- IT_ADMIN
- Domain Owner Approval
- Step-up Authentication
- Immutable Audit

---

# 30. Security Audit

必须审计：

- Create / Cancel / Reopen / Close
- Assignment
- Internal Message
- Cross-queue Access
- Applied Approval
- Tool Execution Request
- Escalation
- Reconciliation
- Replay / Correction
- Compensation
- Sensitive Authorization Denial
- Admin / Auditor Sensitive Read
- Data Export

Audit Record：

```text
auditId
occurredAt
actorType
actorId
clientId
action
resourceType
resourceId
decision
reasonCode
scopes
queueContext
authenticationLevel
traceId
safeMetadata
```

Audit Append-only。

---

# 31. Audit Minimization

禁止记录：

- Access Token
- Raw JWT
- Message Body
- Ticket Description
- Secret
- Full User Agent
- Raw IP，除非政策明确要求

允许记录：

- Hash
- Decision
- Scope
- Resource ID
- Action
- Trace ID

---

# 32. Database Authorization

数据库角色：

```text
ticket_migration
ticket_app
ticket_readonly
```

`ticket_app`：

- 必要 DML
- 无 DDL
- 无跨 Schema 写权限
- History / Audit 表不可普通 Update/Delete

生产人员不得共享 Root Database Account。

MVP 不把 PostgreSQL RLS 作为主要授权机制；业务授权在 Application Layer 显式完成。

---

# 33. Encryption

## In Transit

```text
HTTPS / TLS
PostgreSQL TLS
RabbitMQ TLS
Internal API TLS
```

## At Rest

```text
Encrypted Disk
Encrypted Backup
Secret Manager
```

MVP 暂不进行所有字段级加密。

未来候选：

- Requester ID
- Message Body
- Resolution Summary
- Audit Identity

---

# 34. Web Security

## CORS

仅允许已知 Origin：

```text
https://app.opsmind.example
http://localhost:5173
```

禁止 Credentials 配置下使用：

```text
Access-Control-Allow-Origin: *
```

## CSRF

Bearer Token 模式降低 CSRF 风险。

Cookie Session 模式必须使用：

```text
CSRF Token
SameSite
Secure
HttpOnly
```

## XSS

- 默认 Escape
- 禁止直接 `dangerouslySetInnerHTML`
- Markdown 严格 Sanitization
- Attachment Filename Encoding
- 用户内容不直接拼接 HTML Email

---

# 35. Injection and Mass Assignment

## SQL Injection

- Prepared Statement
- JPA Binding
- Sort Allowlist
- 不拼接 Client Column

## Log Injection

- Structured Logging
- 控制换行
- 不记录 Body

## Prompt Injection

- 用户文本视为 Data
- Tool Action 来自 Structured Schema
- Policy Evaluation
- Tool Catalog Allowlist
- Approval
- Tool Gateway Revalidation

## Mass Assignment

Employee DTO 不允许：

```text
requesterId
status
priority
category
assignedTeam
approvalId
activeWorkflowId
```

---

# 36. Rate Limiting

| API | 建议限制 |
|---|---:|
| Create Ticket | 10/min/user |
| Add Message | 30/min/user |
| Get/List | 120/min/user |
| Cancel/Reopen/Confirm | 10/min/user |
| Internal Command | 300/min/client |
| Recovery Command | 10/min/operator |

结合：

- Per User
- Per Client
- Per IP Hash
- Burst Limit
- Global Safety Limit

---

# 37. Attachment Security

Attachment 必须：

- 存入受控 Object Storage
- 使用短期 Signed URL
- Malware Scan
- MIME Sniffing
- Size Limit
- Filename Sanitization
- Ownership Check
- Download Audit

Ticket Message 仅保存：

```text
attachmentId
```

不保存 Public URL 或本地路径。

---

# 38. Logging and Telemetry Security

允许：

```text
traceId
correlationId
ticketId
eventId
workflowId
errorCode
actorType
authorizationDecision
payloadHash
```

禁止：

```text
Authorization Header
JWT
Password
Token
Message Body
Description
Full Event Payload
Raw Dependency Response
```

OpenTelemetry 和 LangSmith 必须进行额外 Redaction。

TicketId 可以进入 Structured Log，但不能作为 Prometheus Label。

---

# 39. STRIDE Threat Model

```text
Spoofing
Tampering
Repudiation
Information Disclosure
Denial of Service
Elevation of Privilege
```

## Spoofing Controls

- JWT Signature
- Issuer / Audience
- PKCE
- Short Token
- Service Account
- TLS / mTLS

## Tampering Controls

- TLS
- JSON Schema
- Payload Hash
- Producer Allowlist
- Stable IDs
- Optional JWS
- Database Constraint

## Repudiation Controls

- Actor Identity
- Command / Event ID
- Audit
- Before / After Hash
- Approval Reference
- Four-eyes

## Information Disclosure Controls

- Ownership
- Queue Scope
- Field Visibility
- Redaction
- Secret Detection
- Encrypted Backup

## DoS Controls

- Rate Limit
- Backpressure
- Retry Budget
- Circuit Breaker
- Payload Limit
- Transaction Timeout
- DLQ

## Elevation Controls

- Explicit DTO
- Scope Matrix
- No Generic Status API
- Service-specific Client
- Domain Guard
- Step-up Auth
- Separation of Duties

---

# 40. Abuse Cases

## 40.1 IDOR：读取他人 Ticket

防护：

- UUID / ULID
- Ownership Check
- Unauthorized 返回 404
- Rate Limit
- Enumeration Alert

## 40.2 伪造 requesterId

防护：

- Employee DTO 无 requesterId
- JWT Subject 为 Source of Truth

## 40.3 Support 越 Queue

防护：

- Queue Projection
- Assignment / Temporary Grant
- Sensitive Read Audit
- Repeated Denial Alert

## 40.4 Service Token 调用 Admin API

防护：

- Audience
- Principal Type
- Scope
- Client Allowlist
- Internal/Public Route Separation

## 40.5 伪造 Approval Event

防护：

- RabbitMQ ACL
- Producer Allowlist
- Schema
- Reference Match
- Expiration
- DLQ

## 40.6 Prompt Injection 执行 Tool

防护：

- User Text 不直接定义 Action
- Structured Output
- Policy
- Approval
- Tool Allowlist
- Target Scope Validation

## 40.7 重放旧 Approval

防护：

- Approval 绑定 Ticket / Workflow / Action
- Expiration
- Pending Action Status
- Event Dedup
- Old Workflow Stale Check

## 40.8 滥用 Recovery Replay

防护：

- Dedicated Scope
- Step-up Auth
- Four-eyes
- Replay Eligibility
- Immutable Audit

---

# 41. Security Headers

推荐：

```text
Content-Security-Policy
X-Content-Type-Options: nosniff
Referrer-Policy
Permissions-Policy
Strict-Transport-Security
```

---

# 42. Secure Development

CI 至少包含：

```text
Secret Scan
SAST
Dependency Scan
Container Scan
SBOM
OpenAPI Security Contract Test
Event Schema Test
Authorization Test
Infrastructure Misconfiguration Scan
```

以下变更必须 Security Review：

- 新 Role / Scope
- 新 Internal API
- 新高风险 Tool Action
- 新 Sensitive Field
- 新 Event Producer
- Replay / Correction
- Cross-queue Access

---

# 43. Security Tests

## Authentication

```text
rejectExpiredToken
rejectWrongIssuer
rejectWrongAudience
rejectUnknownKidAfterRefreshFailure
rejectUserTokenOnInternalApi
rejectServiceTokenOnEmployeeApi
```

## Authorization

```text
employeeCanReadOwnTicket
employeeCannotReadOtherUsersTicket
supportCanReadAuthorizedQueue
supportCannotReadUnauthorizedQueue
auditorCannotModifyTicket
adminCannotBypassStateMachine
```

## Visibility

```text
employeeCannotSeeInternalMessage
agentCannotReadRecoveryAudit
auditorReceivesRedactedBody
toolGatewayDoesNotReceiveTicketDescription
```

## Event Trust

```text
rejectApprovalFromWrongProducer
rejectToolEventWithWrongAction
rejectExpiredApproval
rejectEventWithSecret
rejectEventIdPayloadConflict
```

## Recovery

```text
replayRequiresScope
correctionRequiresApproval
compensationRequiresNewApproval
operatorCannotApproveOwnHighRiskRecovery
```

---

# 44. Penetration Test Scenarios

- Ticket IDOR
- Queue Authorization Bypass
- JWT Audience Confusion
- Scope Escalation
- CORS Misconfiguration
- Stored XSS
- SQL Injection in Sort
- Log Injection
- Prompt Injection
- Event Producer Spoofing
- Approval Replay
- Recovery Abuse
- Malware Attachment
- Secret Leakage into Trace

---

# 45. Metrics and Alerts

Metrics：

```text
ticket_authentication_failure_total
ticket_authorization_denied_total
ticket_cross_queue_denied_total
ticket_sensitive_read_total
ticket_secret_detected_total
ticket_event_producer_rejected_total
ticket_event_signature_invalid_total
ticket_recovery_authorization_denied_total
ticket_step_up_required_total
ticket_rate_limited_total
ticket_suspicious_resource_enumeration_total
```

禁止 Label：

```text
subject
ticket_id
token_id
ip
email
```

Critical Alert：

```text
Secret detected
Wrong Approval / Tool Producer
Event Signature Failure
Cross-Ticket Reference Corruption
Recovery without approval
Unauthorized DB write
```

Warning：

```text
Repeated cross-queue denials
Enumeration pattern
Authentication failure spike
Unusual sensitive reads
Expired approval replay
Rate-limit spike
```

---

# 46. Incident Response

```text
Detect
→ Contain
→ Preserve Evidence
→ Revoke Credential / Session
→ Restrict Automation
→ Open Security Reconciliation
→ Assess Ticket Impact
→ Recover
→ Verify
→ Postmortem
```

可执行动作：

- Disable Keycloak Client
- Rotate Secret
- Revoke Session
- Pause Consumer
- Block Routing Key
- Restrict Tool Action
- Escalate affected Tickets
- Run Integrity Scan

---

# 47. Data Model Increment

后续建议增加：

```text
ticket.security_audit_records
ticket.temporary_access_grants
ticket.sensitive_read_audit
```

如果存在统一 Audit Platform，可以通过 Outbox 发布 Audit Event，但高风险业务事务仍应保存本地 Audit Reference。

---

# 48. Acceptance Criteria

- [x] Trust Boundary 已定义。
- [x] Keycloak Realm、Client 和 Flow 已定义。
- [x] JWT Validation 已定义。
- [x] Role、Scope 和 Principal Model 已定义。
- [x] Resource Ownership 已定义。
- [x] Queue Authorization 已定义。
- [x] Cross-queue Access 已定义。
- [x] Field Visibility 已定义。
- [x] Data Classification、PII 和 Secret Handling 已定义。
- [x] Internal Service Identity 和 Scope Matrix 已定义。
- [x] RabbitMQ ACL 和 Producer Validation 已定义。
- [x] Approval、Tool、Verification Trust Boundary 已定义。
- [x] Step-up Authentication 和 Separation of Duties 已定义。
- [x] Recovery Authorization 和 Audit 已定义。
- [x] Web、Injection、Rate Limit 和 Attachment Security 已定义。
- [x] STRIDE 与 Abuse Cases 已定义。
- [x] Security Test、Metrics、Alert 和 Incident Response 已定义。

---

# 49. 下一步

下一份文档：

```text
12-observability-and-audit_CN.md
12-observability-and-audit_EN.md
```

该文档将定义 OpenTelemetry Trace、Structured Log、Metrics、Cardinality、Audit Event、SLI/SLO、Dashboard、Alert、Golden Path Trace、PII Redaction 和 Incident Debugging。
