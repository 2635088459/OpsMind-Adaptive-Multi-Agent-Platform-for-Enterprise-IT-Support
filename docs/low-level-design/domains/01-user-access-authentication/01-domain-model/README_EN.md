# 01 Domain Model

## 1. Boundary and ownership

Keycloak is authoritative for passwords, MFA secrets, authenticator enrollment, OIDC sessions, and signing private keys. Domain 01 never copies credentials. It owns OpsMind user mappings, role assignments, authorization scopes, session-revocation metadata, step-up challenges, workload-identity mappings, and immutable authorization decisions. Ticket, Workflow, Tool, Memory, and governance Policy state remain owned by domains 02–06.

## 2. Aggregate roots

### `UserIdentity`

| Field | Type | Constraint |
|---|---|---|
| `userIdentityId` | UUID | Aggregate ID |
| `tenantId` | UUID | Required isolation boundary |
| `issuer` | String | Normalized HTTPS issuer |
| `subject` | String | Immutable IdP `sub` |
| `username` | String | Mutable display/login hint; never an authorization key |
| `displayName`, `email` | String? | Minimized and retention controlled |
| `identityType` | Enum | `HUMAN`, `WORKLOAD` |
| `status` | Enum | `ACTIVE`, `DISABLED`, `DEPROVISIONED` |
| `profileVersion` | Long | Upstream mapping version |
| `linkedAt`, `lastSyncedAt` | Instant | Synchronization evidence |
| `disabledAt`, `deprovisionedAt` | Instant? | Lifecycle timestamps |
| `createdAt`, `updatedAt` | Instant | Audit timestamps |
| `version` | Long | Optimistic lock |

The identity key is `(tenantId, issuer, subject)`; email and username never replace `subject`.

### `RoleAssignment`

Fields: `roleAssignmentId`, `tenantId`, `userIdentityId`, `roleCode`, `scopeType`, `scopeId`, `permissions`, `status`, `validFrom`, `validUntil`, `grantedBy`, `grantReason`, `revokedBy`, `revokedAt`, `revocationReason`, `createdAt`, `updatedAt`, `version`. Roles include `EMPLOYEE`, `SUPPORT_AGENT`, `APPROVER`, `IT_ADMIN`, `PLATFORM_ADMIN`, `AUDITOR`; scopes include `SELF`, `TENANT`, `SUPPORT_QUEUE`, `RESOURCE`.

### `UserSession`

Fields: `userSessionId`, `tenantId`, `issuer`, `subject`, `idpSessionIdHash`, `tokenIdHash`, `clientId`, `authenticationTime`, `assuranceLevel`, `authenticationMethods`, `deviceIdHash`, `startedAt`, `lastSeenAt`, `expiresAt`, `status`, `revokedAt`, `revokedBy`, `revocationReason`, `createdAt`, `updatedAt`, `version`. Only hashes and metadata are stored—never access, refresh, or ID tokens.

### `StepUpChallenge`

Fields: `stepUpChallengeId`, `challengeKey`, `tenantId`, `issuer`, `subject`, `userSessionId`, `requestedAction`, `resourceType`, `resourceId`, `requiredAssuranceLevel`, `requiredMethods`, `nonceHash`, `status`, `attemptCount`, `maxAttempts`, `createdAt`, `expiresAt`, `verifiedAt`, `proofIdHash`, `consumedAt`, `correlationId`, `version`.

### `ServiceIdentity`

Fields: `serviceIdentityId`, `tenantId`, `issuer`, `subject`, `clientId`, `serviceName`, `allowedAudiences`, `allowedScopes`, `status`, `validFrom`, `validUntil`, `lastSeenAt`, `disabledAt`, `createdAt`, `updatedAt`, `version`. Client secrets and private keys are never stored.

## 3. Immutable facts and value objects

`AuthorizationDecision` stores `decisionId`, `decisionKey`, `inputHash`, principal/session snapshot, action/resource, `effect`, evaluated roles/scopes, ownership result, assurance, reason codes, constraints, created/expires/correlation. `effect` is `ALLOW`, `DENY`, or `REQUIRE_STEP_UP`.

Value objects are `ExternalSubject(issuer, subject)`, `TenantId`, `RoleCode`, `ResourceScope`, `AuthenticationAssurance(acr, amr, authTime)`, `AuthorizationTarget(action, resourceType, resourceId)`, `ReasonCode`, and `CorrelationId`. They normalize and validate at construction.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-007, SPEC-UA-008, SPEC-UA-011, SPEC-UA-016`
