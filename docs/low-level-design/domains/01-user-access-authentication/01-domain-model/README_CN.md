# 01 领域模型

## 1. 边界与数据所有权

Keycloak 是密码、MFA secret、认证器注册、OIDC session 和签名私钥的事实来源。01 不复制 credential；它拥有 OpsMind 用户映射、角色分配、授权范围、会话撤销元数据、step-up challenge、workload identity 映射和不可变授权决策。Ticket、Workflow、Tool、Memory 和治理 Policy 状态仍归 02–06。

## 2. 聚合根

### `UserIdentity`

| 字段 | 类型 | 约束 |
|---|---|---|
| `userIdentityId` | UUID | 聚合 ID |
| `tenantId` | UUID | 必填；隔离边界 |
| `issuer` | String | 规范化 HTTPS issuer |
| `subject` | String | IdP `sub`；不可变 |
| `username` | String | 可变展示/登录提示，不作为授权键 |
| `displayName`, `email` | String? | 最小化保存；受保留策略控制 |
| `identityType` | Enum | `HUMAN`, `WORKLOAD` |
| `status` | Enum | `ACTIVE`, `DISABLED`, `DEPROVISIONED` |
| `profileVersion` | Long | 上游映射版本 |
| `linkedAt`, `lastSyncedAt` | Instant | 同步证据 |
| `disabledAt`, `deprovisionedAt` | Instant? | 生命周期时间 |
| `createdAt`, `updatedAt` | Instant | 审计时间 |
| `version` | Long | 乐观锁 |

唯一身份键为 `(tenantId, issuer, subject)`。邮箱、用户名不能替代 `subject`。

### `RoleAssignment`

字段：`roleAssignmentId`, `tenantId`, `userIdentityId`, `roleCode`, `scopeType`, `scopeId`, `permissions`, `status`, `validFrom`, `validUntil`, `grantedBy`, `grantReason`, `revokedBy`, `revokedAt`, `revocationReason`, `createdAt`, `updatedAt`, `version`。角色包括 `EMPLOYEE`, `SUPPORT_AGENT`, `APPROVER`, `IT_ADMIN`, `PLATFORM_ADMIN`, `AUDITOR`；范围包括 `SELF`, `TENANT`, `SUPPORT_QUEUE`, `RESOURCE`。

### `UserSession`

字段：`userSessionId`, `tenantId`, `issuer`, `subject`, `idpSessionIdHash`, `tokenIdHash`, `clientId`, `authenticationTime`, `assuranceLevel`, `authenticationMethods`, `deviceIdHash`, `startedAt`, `lastSeenAt`, `expiresAt`, `status`, `revokedAt`, `revokedBy`, `revocationReason`, `createdAt`, `updatedAt`, `version`。只保存 hash/metadata，不保存 access、refresh 或 ID token。

### `StepUpChallenge`

字段：`stepUpChallengeId`, `challengeKey`, `tenantId`, `issuer`, `subject`, `userSessionId`, `requestedAction`, `resourceType`, `resourceId`, `requiredAssuranceLevel`, `requiredMethods`, `nonceHash`, `status`, `attemptCount`, `maxAttempts`, `createdAt`, `expiresAt`, `verifiedAt`, `proofIdHash`, `consumedAt`, `correlationId`, `version`。

### `ServiceIdentity`

字段：`serviceIdentityId`, `tenantId`, `issuer`, `subject`, `clientId`, `serviceName`, `allowedAudiences`, `allowedScopes`, `status`, `validFrom`, `validUntil`, `lastSeenAt`, `disabledAt`, `createdAt`, `updatedAt`, `version`。不保存 client secret 或私钥。

## 3. 不可变事实和值对象

`AuthorizationDecision` 保存 `decisionId`, `decisionKey`, `inputHash`, principal/session snapshot、action/resource、`effect`, evaluated roles/scopes、ownership result、assurance、reason codes、constraints、created/expires/correlation。`effect` 为 `ALLOW`, `DENY`, `REQUIRE_STEP_UP`。

值对象包括 `ExternalSubject(issuer, subject)`, `TenantId`, `RoleCode`, `ResourceScope`, `AuthenticationAssurance(acr, amr, authTime)`, `AuthorizationTarget(action, resourceType, resourceId)`, `ReasonCode`, `CorrelationId`。值对象创建时完成规范化和校验。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-007, SPEC-UA-008, SPEC-UA-011, SPEC-UA-016`
