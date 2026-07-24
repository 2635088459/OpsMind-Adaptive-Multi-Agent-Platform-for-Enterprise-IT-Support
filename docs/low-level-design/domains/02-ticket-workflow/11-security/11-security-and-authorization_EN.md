# OpsMind Ticket Workflow — 11 Security and Authorization

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Security and Authorization Design  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Identity Platform:** Keycloak  
> **Authorization Model:** RBAC + Resource Ownership + Queue Scope + Service Scope + Policy Guard  
> **Dependencies:** `01-domain-model_EN.md` through `10-error-handling-and-reconciliation_EN.md`  
> **Recommended Path:** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/11-security-and-authorization_EN.md`

---

## 1. Purpose

This document defines authentication, authorization, cross-service trust, data visibility, security audit, and threat protection for Ticket Workflow.

Core goals:

```text
Every request and event has a trusted identity.
Every action satisfies role, scope, resource ownership, queue access, and the state machine.
No service can bypass approval, policy, or verification through Ticket APIs.
Secrets never enter Tickets, events, logs, traces, metrics, or LangSmith.
High-risk recovery requires strong authentication, approval, audit, and verification.
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

The system treats browser input, token claims, service configuration, events, agent output, tool results, and support activity as untrusted until validated.

---

# 3. Security Principles

## Deny by Default

```text
No role
No scope
No ownership
No queue access
No trusted service identity
No valid business guard
→ DENY
```

## Least Privilege

Users, support staff, administrators, and services receive only the permissions required for their responsibilities.

## Defense in Depth

Controls exist at the gateway, JWT validator, endpoint, application authorization layer, domain guard, database constraint, event consumer, and audit layer.

## Authentication Is Not Authorization

A valid token does not grant access to every Ticket.

## Authorization Does Not Bypass Business State

A principal with `tickets:close` still cannot close an `EXECUTING` Ticket.

---

# 4. Keycloak Design

Environment-isolated realms:

```text
opsmind-local
opsmind-ci
opsmind-demo
opsmind-staging
opsmind-prod
```

Production and non-production do not share signing keys, client secrets, service accounts, sessions, or broker credentials.

Recommended clients:

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

Web authentication:

```text
Authorization Code Flow
PKCE S256
Public Client
```

Backend services:

```text
Confidential Client
Client Credentials
Service Account Enabled
```

Implicit Flow and Resource Owner Password Grant are prohibited.

Production may add mTLS, workload identity, or DPoP.

---

# 5. JWT Validation

Ticket Service validates:

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

It rejects unsigned tokens, unknown algorithms, wrong issuer or audience, expired tokens, future `nbf`, user tokens on service-only routes, service tokens on ownership APIs, and missing scopes.

JWKS rules:

- HTTPS only
- Cached public keys
- Key rotation support
- One refresh for an unknown `kid`
- Fail closed if refresh fails
- Recommended clock skew: 60 seconds

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

Principal types:

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

The context comes from trusted identity and authorization data, not request payloads.

---

# 7. Roles and Scopes

Realm roles:

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

A universal `SUPER_ADMIN` is avoided.

Read scopes:

```text
tickets:own:read
tickets:queue:read
tickets:any:read
tickets:timeline:read
tickets:audit:read
tickets:context:read
```

Write scopes:

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

Internal scopes:

```text
tickets:triage:start
tickets:classify
tickets:workflow:associate
tickets:user-input:request
tickets:verification:start
tickets:execution:consume
```

Recovery scopes:

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

Employees receive create, own-read, message, cancel, reopen, confirm, and timeline scopes, restricted to their own Tickets.

Support receives queue read, internal message, assignment, escalation, close, and user-input scopes, restricted by queue, assignment, or temporary grant.

Managers receive queue management and reconciliation-read permissions.

Administrators receive broad read, assignment, escalation, retry, reconciliation operation, and event replay, but cannot bypass approval, verification, or state-machine guards.

Auditors are read-only.

---

# 9. Resource Ownership

Employee access requires:

```text
ticket.requesterId == principal.subject
```

This applies to read, message, cancel, reopen, confirm, and requester timeline operations.

Employee DTOs never accept a requester ID that overrides the authenticated subject.

Unauthorized access to another user's Ticket returns:

```text
404 TICKET_NOT_FOUND
```

to reduce resource enumeration.

---

# 10. Queue-based Authorization

Support access requires the endpoint scope and one of:

```text
ticket.currentTeamId in principal.queueMemberships
ticket.currentSupportUserId == principal.subject
valid temporary cross-queue grant
tickets:any:read
```

The MVP may use a Keycloak group claim:

```json
{
  "support_queues": [
    "IDENTITY_SUPPORT",
    "DEVICE_SUPPORT"
  ]
}
```

Large memberships should use an authorization service and short-lived cache instead of oversized JWTs.

---

# 11. Temporary Cross-queue Access

A grant contains:

```text
grantId
operatorId
ticketId or queueId
reasonCode
grantedBy
expiresAt
auditReference
```

Recommended maximum duration:

```text
8 hours
```

Chat-only approval, non-expiring grants, and access created by changing the Ticket team are prohibited.

---

# 12. Authorization Flow

```text
1. Validate JWT
2. Build PrincipalContext
3. Check endpoint scope
4. Load TicketAuthorizationProjection
5. Check ownership, queue, assignment, and temporary grant
6. Apply field-level visibility
7. Execute the use case
8. Validate domain state
9. Audit sensitive activity
```

The authorization projection contains only:

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

Employees see requester-visible information, messages, safe processing summaries, resolution, and SLA information.

They do not see internal notes, prompts, policy internals, credentials, recovery audit, security cases, or another user's information.

Support sees internal notes, assignments, approval summaries, tool summaries, verification summaries, and escalations for authorized queues.

Auditors receive append-only evidence and redacted message content by default.

Services receive only the minimum context needed for their task.

---

# 14. Data Classification

```text
PUBLIC
INTERNAL
SENSITIVE
SECRET
```

| Data | Classification |
|---|---|
| Status and category | INTERNAL |
| Ticket and display IDs | INTERNAL |
| Requester ID | SENSITIVE |
| Title and description | SENSITIVE |
| Message body | SENSITIVE |
| Resolution summary | SENSITIVE |
| Password, token, API key | SECRET |

Rule:

```text
SECRET data is not allowed in the Ticket domain.
```

---

# 15. PII Minimization

Events and internal APIs transfer only required fields.

Recommended pseudonymous requester identifier:

```text
HMAC-SHA-256(service-controlled key, requesterId)
```

A plain unsalted hash of an enumerable identifier is insufficient.

Hashed identifiers remain sensitive pseudonymous data.

---

# 16. Secret Handling

Secrets are forbidden in:

```text
Ticket descriptions
Ticket messages
Outbox payloads
Event payloads
Status history
Reconciliation evidence bodies
Application logs
OpenTelemetry
Prometheus
LangSmith
```

Secrets are stored in Docker secrets for local or demo use and a cloud secret manager for production.

Independent rotation is required for Keycloak, RabbitMQ, database, tool, and encryption credentials.

---

# 17. Secret Detection and Redaction

Scanning applies to:

- Ticket creation
- Message creation
- Agent-generated requester content
- Event ingestion
- Reconciliation evidence
- Attachment metadata

High-confidence secrets are blocked or quarantined, the operation fails closed, and a security alert is created without copying the secret value.

---

# 18. Content and Prompt Safety

Agent-generated requester content must not ask for passwords, MFA codes, recovery codes, or tokens; reveal prompts or policy; expose another user's information; or provide unsafe instructions.

Fixed policy:

```text
OpsMind never asks users to submit passwords, access tokens, recovery codes, or MFA one-time codes through a Ticket.
```

User text is data, not a system instruction.

---

# 19. Internal Service Identity

Each service has a separate:

```text
Keycloak client
Service account
Client secret or workload identity
RabbitMQ credential
```

Shared service identities are prohibited because they prevent attribution, least privilege, independent revocation, and containment.

---

# 20. Internal Service Scope Matrix

| Service | Allowed Scope |
|---|---|
| Agent Runtime | context read, triage start, classify, user-input request, verification start |
| Approval Service | publish approval and policy events; no generic Ticket write |
| Tool Gateway | consume execution-ready; no Ticket status endpoint |
| Verification Service | consume verification request; publish result |
| Notification Service | limited notification context |
| Evaluation Service | redacted closed-ticket outcome |
| Support Operations | reconciliation scopes |
| Scheduler | auto-close and SLA-specific scopes |

Internal tokens require:

```text
aud = ticket-workflow-service
```

---

# 21. RabbitMQ Security

Each service uses an independent RabbitMQ identity and environment-specific virtual host.

Ticket Workflow may publish only Ticket routing keys and consume only its inbound queues.

Approval, Tool, and Verification services receive narrowly scoped publish and consume permissions.

---

# 22. Event Trust Validation

Broker authentication alone does not prove business validity.

Consumers validate:

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

Producer allowlist examples:

```text
approval.granted → policy-approval-service
tool.execution.completed → tool-gateway-service
```

A wrong producer triggers:

```text
EVENT_PRODUCER_NOT_ALLOWED
DLQ
Security Alert
```

Production may add JWS event signatures, but signatures do not replace reference validation.

---

# 23. Approval Trust Boundary

Approval Service owns the approval ID, decision, approver, expiry, and risk policy.

Before execution, Ticket Service matches:

```text
ticketId
workflowId
actionId
actionType
approvalId
riskLevel
expiresAt
```

The approval must satisfy:

```text
approvedAt <= expiresAt
```

and cannot be reused across Tickets, workflows, actions, action types, or risk contexts.

---

# 24. Tool Trust Boundary

Ticket Service never accesses tool credentials.

It publishes only action, policy, execution, and business-idempotency references.

Tool Gateway revalidates:

```text
allowlisted action
matching policy decision
matching target scope
unique execution identity
internally acquired credential
```

This prevents confused-deputy attacks.

---

# 25. Verification Trust Boundary

Verification Service cannot directly mutate Ticket state.

Ticket Service validates producer, VerificationId, WorkflowId, resolution attempt, cycle, evidence, and terminal-result conflicts.

Only a trusted current-cycle verification success may resolve a Ticket.

---

# 26. High-risk Operations

Examples:

- Permission changes
- MFA reset
- Account disablement
- Device wipe
- Credential rotation
- Compensation
- Correction event
- Cross-queue access
- Data repair

Requirements:

```text
Strong authentication
Explicit scope
Reason code
Approval
Audit
Verification
```

---

# 27. Step-up Authentication

The following may require an MFA-authenticated session less than fifteen minutes old:

- Compensation approval
- Event correction
- Security reconciliation
- Data repair
- Temporary cross-queue grant
- Bulk operation

---

# 28. Separation of Duties

One person cannot unconditionally propose, approve, and verify a high-risk recovery.

The MVP uses four-eyes approval:

- One operator proposes.
- A different approver authorizes.

---

# 29. Reconciliation Authorization

Dedicated scopes control reconciliation read, operation, approval, replay, correction, and compensation.

Correction events require administrator authority, domain-owner approval, step-up authentication, and immutable audit.

---

# 30. Security Audit

Audited actions include:

- Create, cancel, reopen, and close
- Assignment
- Internal messages
- Cross-queue access
- Applied approval references
- Tool execution requests
- Escalation
- Reconciliation
- Replay and correction
- Compensation
- Sensitive denials
- Sensitive administrator or auditor reads
- Data export

Audit record:

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

Audit records are append-only.

---

# 31. Audit Minimization

Audit excludes access tokens, raw JWTs, message bodies, descriptions, secrets, complete user agents, and raw IP addresses unless policy requires them.

Hashes, decisions, scopes, resource IDs, actions, and trace IDs are allowed.

---

# 32. Database Authorization

Recommended roles:

```text
ticket_migration
ticket_app
ticket_readonly
```

The application role receives required DML only, no DDL or cross-schema write access. History and audit tables deny ordinary updates and deletes.

Production operators do not share a root database account.

PostgreSQL row-level security is not the primary MVP authorization mechanism; application authorization remains explicit and testable.

---

# 33. Encryption

In transit:

```text
HTTPS / TLS
PostgreSQL TLS
RabbitMQ TLS
Internal API TLS
```

At rest:

```text
Encrypted disk
Encrypted backups
Secret-manager encryption
```

The MVP defers universal field-level encryption. Future candidates include requester IDs, message bodies, resolution summaries, and audit identities.

---

# 34. Web Security

CORS allows only known origins and never combines wildcard origins with credentials.

Bearer-token APIs reduce traditional CSRF exposure. Cookie-based sessions require CSRF tokens, SameSite, Secure, and HttpOnly protections.

Ticket content is untrusted and escaped by default. Markdown is sanitized, dangerous HTML rendering is avoided, and filenames are encoded.

---

# 35. Injection and Mass Assignment

SQL uses prepared statements, parameter binding, and sort-field allowlists.

Logs are structured and do not include raw body content.

User text cannot directly define a tool action. Structured agent output, policy evaluation, allowlists, approval, and gateway validation protect execution.

Employee DTOs cannot set requester, status, priority, category, assignment, approval, or workflow fields.

---

# 36. Rate Limiting

| API | Recommended Limit |
|---|---:|
| Create Ticket | 10/min/user |
| Add Message | 30/min/user |
| Get/List | 120/min/user |
| Cancel/Reopen/Confirm | 10/min/user |
| Internal Command | 300/min/client |
| Recovery Command | 10/min/operator |

Controls may combine user, client, IP hash, burst, and global safety limits.

---

# 37. Attachment Security

Attachments use controlled object storage, expiring signed URLs, malware scanning, MIME sniffing, size limits, filename sanitization, ownership checks, and download audit.

Ticket messages store attachment IDs, not public URLs or local paths.

---

# 38. Logging and Telemetry Security

Allowed fields:

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

Forbidden fields:

```text
Authorization header
JWT
password
token
message body
description
complete event payload
raw dependency response
```

OpenTelemetry and LangSmith receive additional redaction.

Ticket IDs may appear in structured logs but not as Prometheus labels.

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

Controls include JWT validation, TLS, schema validation, payload hashes, producer allowlists, stable identifiers, audit, field visibility, rate limits, retry budgets, no generic status endpoint, step-up authentication, and separation of duties.

---

# 40. Abuse Cases

## Reading Another User's Ticket

Controls:

- UUID or ULID
- Ownership check
- Unauthorized 404
- Rate limit
- Enumeration alert

## Forged Requester ID

Employee DTOs omit requester ID; the JWT subject is authoritative.

## Cross-queue Support Access

Every access checks queue, assignment, or temporary grant and audits sensitive reads.

## Service Token on Admin API

Audience, principal type, scope, client allowlist, and route separation prevent this.

## Forged Approval Event

Broker ACL, producer allowlist, schema, reference matching, expiration, and DLQ protect the boundary.

## Prompt Injection to Execute a Tool

User text does not directly define actions. Structured output, policy, approval, allowlists, and target validation are required.

## Replaying an Old Approval

Approval identity is bound to Ticket, workflow, action, type, and expiration.

## Recovery Replay Abuse

Dedicated scope, step-up authentication, four-eyes approval, replay eligibility, and immutable audit are required.

---

# 41. Security Headers

Recommended:

```text
Content-Security-Policy
X-Content-Type-Options: nosniff
Referrer-Policy
Permissions-Policy
Strict-Transport-Security
```

---

# 42. Secure Development

CI includes:

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

Security review is required for new roles, scopes, internal APIs, high-risk actions, sensitive fields, event producers, replay, correction, and cross-queue access.

---

# 43. Security Tests

Authentication:

```text
rejectExpiredToken
rejectWrongIssuer
rejectWrongAudience
rejectUnknownKidAfterRefreshFailure
rejectUserTokenOnInternalApi
rejectServiceTokenOnEmployeeApi
```

Authorization:

```text
employeeCanReadOwnTicket
employeeCannotReadOtherUsersTicket
supportCanReadAuthorizedQueue
supportCannotReadUnauthorizedQueue
auditorCannotModifyTicket
adminCannotBypassStateMachine
```

Visibility:

```text
employeeCannotSeeInternalMessage
agentCannotReadRecoveryAudit
auditorReceivesRedactedBody
toolGatewayDoesNotReceiveTicketDescription
```

Event trust:

```text
rejectApprovalFromWrongProducer
rejectToolEventWithWrongAction
rejectExpiredApproval
rejectEventWithSecret
rejectEventIdPayloadConflict
```

Recovery:

```text
replayRequiresScope
correctionRequiresApproval
compensationRequiresNewApproval
operatorCannotApproveOwnHighRiskRecovery
```

---

# 44. Penetration Test Scenarios

- Ticket IDOR
- Queue authorization bypass
- JWT audience confusion
- Scope escalation
- CORS misconfiguration
- Stored XSS
- SQL injection in sorting
- Log injection
- Prompt injection
- Event producer spoofing
- Approval replay
- Recovery abuse
- Malware attachment
- Secret leakage into traces

---

# 45. Metrics and Alerts

Metrics:

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

High-cardinality labels such as subject, Ticket ID, token ID, IP, and email are forbidden.

Critical alerts include secret detection, wrong Approval or Tool producer, signature failures, cross-Ticket corruption, recovery without approval, and unauthorized database writes.

---

# 46. Incident Response

```text
Detect
→ Contain
→ Preserve Evidence
→ Revoke Credential or Session
→ Restrict Automation
→ Open Security Reconciliation
→ Assess Ticket Impact
→ Recover
→ Verify
→ Postmortem
```

Containment may disable a Keycloak client, rotate secrets, revoke sessions, pause consumers, block routing keys, restrict tool actions, escalate affected Tickets, and run integrity scans.

---

# 47. Data-model Increment

A later data-model revision should add:

```text
ticket.security_audit_records
ticket.temporary_access_grants
ticket.sensitive_read_audit
```

A central audit platform may consume audit events, but high-risk business transactions should keep a local atomic audit reference.

---

# 48. Acceptance Criteria

- [x] Trust boundaries defined
- [x] Keycloak realms, clients, and flows defined
- [x] JWT validation defined
- [x] Roles, scopes, and principal model defined
- [x] Resource ownership defined
- [x] Queue authorization defined
- [x] Cross-queue access defined
- [x] Field visibility defined
- [x] Classification, PII, and secret handling defined
- [x] Internal service identity and scope matrix defined
- [x] RabbitMQ ACL and producer validation defined
- [x] Approval, Tool, and Verification trust boundaries defined
- [x] Step-up authentication and separation of duties defined
- [x] Recovery authorization and audit defined
- [x] Web, injection, rate-limit, and attachment security defined
- [x] STRIDE and abuse cases defined
- [x] Security tests, metrics, alerts, and incident response defined

---

# 49. Next Step

Create:

```text
12-observability-and-audit_CN.md
12-observability-and-audit_EN.md
```

That document will define OpenTelemetry traces, structured logging, metrics, cardinality, audit events, SLIs and SLOs, dashboards, alerts, Golden Path tracing, PII redaction, and incident debugging.
