# 09 Concurrency and Idempotency

- Aggregates use optimistic `version`; APIs expose it through `ETag/If-Match` or a request field. Conflicts return 409 and never overwrite admin changes automatically.
- Commands persist `(tenantId, operation, idempotencyKey, requestHash, responseRef)`. Same key+hash returns the original result; same key+different hash returns `IDEMPOTENCY_CONFLICT`.
- User provisioning relies on UNIQUE `(tenant,issuer,subject)` so concurrent first logins create one mapping.
- Role assignment uses a partial active unique key plus transactional validity-overlap checks.
- Step-up consumption uses atomic conditional update and unique `proofIdHash`; at most one request moves VERIFIED to CONSUMED.
- Session revocation is idempotent: repeated calls return the existing final state and never replace original revoke actor/reason.
- JWKS refresh is single-flight. Unknown `kid` triggers one rate-limited refresh, preventing attacker-controlled refresh storms.
- Cache keys include tenant, issuer, subject, and role/profile version; role-revoke/user-disable events invalidate caches.
- Events deduplicate by `(consumer,eventId)`; worker lease/heartbeat prevents recovery and normal workers processing together.

Time comparisons use server-side UTC and tests inject `Clock`. Configured clock skew is bounded and never enlarged to accept expired tokens.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-006, SPEC-UA-009, SPEC-UA-012, SPEC-UA-018, SPEC-UA-033, SPEC-UA-034`
