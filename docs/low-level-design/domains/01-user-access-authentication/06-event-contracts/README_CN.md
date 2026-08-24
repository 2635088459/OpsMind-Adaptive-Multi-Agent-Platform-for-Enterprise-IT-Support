# 06 事件契约

统一 envelope：`eventId` UUID、`eventType`、`schemaVersion`、`occurredAt`、`producer`, `tenantId`, `correlationId`, `causationId`, `subjectRef`, `payload`。事件不得包含 token、cookie、password、MFA secret、nonce/proof 原值或完整 profile。

## 发布事件

| Event | 关键 payload |
|---|---|
| `identity.user.provisioned.v1` | userIdentityId, issuer, subjectHash, status |
| `identity.user.status.changed.v1` | userIdentityId, from, to, reasonCode |
| `identity.role.assigned.v1` | assignmentId, userIdentityId, roleCode, scope, validUntil |
| `identity.role.revoked.v1` | assignmentId, reasonCode, revokedAt |
| `identity.session.revoked.v1` | sessionId, subjectRef, reasonCode, revokedAt |
| `identity.assurance.verified.v1` | challengeId, subjectRef, assuranceLevel, action/resource hash, expiresAt |
| `identity.service.disabled.v1` | serviceIdentityId, serviceName, disabledAt |
| `identity.security.alert.v1` | alertType, severity, subjectRef, sessionRef, reasonCode |

## 消费事件

- Keycloak/admin adapter：用户禁用、登出、credential compromise 和 group/role 同步事实。
- 06：审批或 break-glass 已批准/拒绝/过期，用于受控高权限流程。
- Platform：service identity/key rotation 和 tenant lifecycle。

消费者以 `(consumerName,eventId)` 去重。Schema 只允许 additive optional changes；字段删除、重命名、语义改变发布新 major。subject 使用 opaque ID/hash；需要 profile 的消费者通过授权 API 查询，禁止在广播事件中复制 PII。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-009, SPEC-UA-012, SPEC-UA-028, SPEC-UA-029`
