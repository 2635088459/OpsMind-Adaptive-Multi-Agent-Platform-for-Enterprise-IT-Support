# 06 Event Contracts

The common envelope contains `eventId` UUID, `eventType`, `schemaVersion`, `occurredAt`, `producer`, `tenantId`, `correlationId`, `causationId`, `subjectRef`, and `payload`. Events never contain tokens, cookies, passwords, MFA secrets, raw nonce/proof values, or complete profiles.

## Published events

| Event | Key payload |
|---|---|
| `identity.user.provisioned.v1` | userIdentityId, issuer, subjectHash, status |
| `identity.user.status.changed.v1` | userIdentityId, from, to, reasonCode |
| `identity.role.assigned.v1` | assignmentId, userIdentityId, roleCode, scope, validUntil |
| `identity.role.revoked.v1` | assignmentId, reasonCode, revokedAt |
| `identity.session.revoked.v1` | sessionId, subjectRef, reasonCode, revokedAt |
| `identity.assurance.verified.v1` | challengeId, subjectRef, assuranceLevel, action/resource hash, expiresAt |
| `identity.service.disabled.v1` | serviceIdentityId, serviceName, disabledAt |
| `identity.security.alert.v1` | alertType, severity, subjectRef, sessionRef, reasonCode |

## Consumed events

- Keycloak/admin adapter: user disable, logout, credential compromise, and group/role synchronization facts.
- Domain 06: approval or break-glass approved/denied/expired facts for controlled privileged flows.
- Platform: service-identity/key rotation and tenant lifecycle.

Consumers deduplicate by `(consumerName,eventId)`. Schemas permit additive optional changes only; field removal, rename, or semantic change requires a new major version. Subjects use opaque IDs/hashes. Consumers needing profiles call an authorized API instead of receiving PII in broadcast events.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-009, SPEC-UA-012, SPEC-UA-028, SPEC-UA-029`
