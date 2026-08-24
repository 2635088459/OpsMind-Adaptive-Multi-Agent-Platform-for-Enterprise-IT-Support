# 14 Testing Strategy

## Test levels

- Domain unit tests: aggregate construction, legal/illegal transitions, invariants, time boundaries, and value-object normalization.
- Application tests: fake ports verify deny-by-default, idempotency, audit/outbox atomic intent, and error mapping.
- Slice tests: Spring Security filter chain, controller validation, method security, and JPA mapper/repository.
- Integration: Testcontainers PostgreSQL, RabbitMQ, and a pinned Keycloak realm; execute Flyway, OIDC code+PKCE, JWT/JWKS rotation, and event publish/consume.
- Contract: 01↔Portal/API Gateway, 01↔02, 01↔06, and workload identity with consumer-driven request/response/event schemas.
- E2E: Employee login/self-service ticket, Support queue scope, Approver step-up, Admin role, logout/revoke, and service call.

## Mandatory security cases

Wrong issuer/audience/signature/alg/kid, expired/nbf/skew, token substitution/replay, forged role header, cross-tenant/horizontal/vertical escalation, session fixation, CSRF/open redirect, step-up action/resource mismatch and double consumption, JWKS poisoning/refresh storm, IdP/DB/broker failure, and secret scanning of logs/traces/events.

## Quality gates

Cover all domain branches and security-denial paths; mutation-test critical authorization and step-up conditions; pass ArchUnit; run migrations from empty and previous versions; allow no unapproved breaking OpenAPI/AsyncAPI diff; pass dependency/SBOM/secret scans without high severity; pass E2E and recovery tests. Fixtures use synthetic identities and never production tokens/PII.

SPEC-UA-035 supplies the cross-domain harness. SPEC-UA-036 collects traceability for all 36 specs, test evidence, residual risks, runbooks, rollback, and release sign-off.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-020 through SPEC-UA-027, SPEC-UA-035, SPEC-UA-036`
