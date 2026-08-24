# 05 API Contracts

Base path is `/internal/identity/v1`; browser login endpoints are exposed through the BFF/API gateway. Error envelope fields are `code`, `message`, `correlationId`, and `retryable`; token-validation internals are never returned.

| Method/Path | Authority | Key request/response fields |
|---|---|---|
| `GET /oauth2/authorization/{provider}` | anonymous | 302; generates state, nonce, and PKCE |
| `GET /login/oauth2/code/{provider}` | callback | Validates then establishes secure HttpOnly/SameSite cookie or returns one-time exchange code |
| `POST /sessions/logout` | authenticated | Session derived from principal; 204 |
| `POST /tokens/introspect-context` | trusted workload | Token is never logged; returns normalized principal, assurance, session status |
| `GET /users/me` | human | Minimum profile plus effective roles/scopes |
| `PUT /users/{id}/status` | `identity:user:admin` | `status`, `reason`; resource version/idempotency key |
| `POST /role-assignments` | `identity:role:grant` | userId, roleCode, scopeType/id, validFrom/until, reason |
| `DELETE /role-assignments/{id}` | `identity:role:revoke` | reason; 204/idempotent |
| `POST /authorization-decisions` | trusted workload | principalRef, action, resource, ownershipContext, requiredAssurance |
| `POST /step-up/challenges` | authenticated | action, resource, requiredAcr/amr; returns challengeId, redirect, expiresAt |
| `POST /step-up/challenges/{id}/verify` | callback/workload | IdP evidence; returns opaque one-time proof handle |
| `POST /step-up/proofs/{handle}/consume` | trusted workload | action/resource/correlation; returns verified assurance |
| `POST /sessions/{id}/revoke` | self/admin | reason; ordinary users cannot revoke another user's session |

HTTP semantics: 401 unauthenticated/invalid credential, 403 trusted identity without authority, 404 nonexistent object only for authorized readers, 409 idempotency/state conflict, 422 semantic invalidity, 429 throttling, 503 identity cannot be verified safely. Internal calls require workload tokens and never accept identity simulation through headers such as `X-User-Role`.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-004, SPEC-UA-005, SPEC-UA-006, SPEC-UA-014, SPEC-UA-018, SPEC-UA-020, SPEC-UA-022, SPEC-UA-023`
