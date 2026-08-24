# 04 Use Cases

| Use case | Actor | Main flow | Failure/compensation |
|---|---|---|---|
| OIDC login | Employee/Support/Admin | Generate state/nonce/PKCE → Keycloak → validate callback → establish principal/session metadata | Reject and audit mismatched state/nonce/code |
| Token validation | API gateway/business service | Route issuer → verify JWKS signature → validate claims → normalize principal | Unknown issuer/audience or expired token returns 401 |
| User synchronization | IdP event/admin | Upsert minimum profile by issuer+subject | Deduplicate repeated events; quarantine conflicts |
| Grant/revoke role | Platform Admin | Validate grantor delegation → mutate assignment → audit/outbox | Overreach or overlap returns 403/409 |
| Authorization evaluation | 02/06/Portal | Build action/resource → role/scope/ownership/assurance → immutable decision | Missing context defaults to DENY |
| Step-up | 06/Approval Center | Create challenge → Keycloak MFA → verify evidence → consume once | Reject expiry, replay, or binding mismatch |
| Logout/revocation | User/Admin/security event | Revoke local session metadata and request IdP end-session/revocation | Keep local revocation and retry if IdP is unavailable |
| Workload identity | Internal service | Client-credentials/mTLS identity → audience/scope validation | Service identities cannot impersonate human actors |
| Break-glass | Authorized admin | Strong authentication + dual/06 approval + bounded time/scope | Auto-expire and emit high-priority audit |

Every command requires `Idempotency-Key` and `X-Correlation-Id`. Queries authorize before reading; read-then-filter is forbidden. Not-found and unauthorized responses do not disclose resource existence.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-005, SPEC-UA-008, SPEC-UA-009, SPEC-UA-012, SPEC-UA-014, SPEC-UA-017, SPEC-UA-019`
