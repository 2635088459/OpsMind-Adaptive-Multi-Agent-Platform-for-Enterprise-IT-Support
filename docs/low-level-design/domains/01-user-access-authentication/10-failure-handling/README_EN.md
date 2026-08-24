# 10 Failure Handling

| Failure | Behavior | Recovery |
|---|---|---|
| Keycloak unavailable | Policy may briefly continue already validated, unexpired low-risk sessions; new login, step-up, and sensitive actions return 503/fail closed | Health probe, backoff, operator alert |
| JWKS endpoint unavailable | Use only keys within max-stale and matching issuer; reject unknown kid | Single-flight refresh/reconcile |
| Database unavailable | Create no identity, role, session, step-up, or authorization decision | Retryable 503; never degrade to allow |
| RabbitMQ unavailable | Business transaction still writes outbox; publication accumulates | Dispatcher retry/poison admin |
| Delayed revocation event | High-risk action performs synchronous session/assurance check | Reconciliation scan |
| Duplicate/out-of-order IdP event | Processed-event dedup; upstream version/time prevents stale overwrite | Quarantine conflict |
| Token clock skew | Validate nbf/exp within a small fixed window | Reject excess and emit metric |
| Lost step-up callback | Challenge remains PENDING until expiry | User restarts; old nonce is not reused |
| Audit-chain mismatch | Stop privileged admin writes and alert | Read-only investigation and controlled repair |

Degraded modes are `NORMAL`, `READ_ONLY_IDENTITY`, `CACHED_VALIDATION_ONLY`, and `FAIL_CLOSED`. No degradation expands authority. Errors do not distinguish nonexistent users from unauthorized visibility. Recovery requires admin authority, reason, idempotency key, and complete audit.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-006, SPEC-UA-009, SPEC-UA-019, SPEC-UA-032, SPEC-UA-033, SPEC-UA-034`
