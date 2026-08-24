# 12 Observability

Logs are structured JSON with `timestamp`, `service`, `environment`, `traceId`, `spanId`, `correlationId`, `tenantId`, `actorRef`, `subjectRef`, `action`, `outcome`, and `reasonCode`; references use opaque IDs/hashes. Tokens, cookies, Authorization headers, passwords, MFA/nonce/proof, and raw email/device/IP are forbidden (mask if operationally required).

Key metrics: authentication success/failure by issuer/client/reason, JWT validation latency/cache hit/unknown kid, active/revoked sessions, authorization allow/deny/step-up, role grant/revoke, step-up requested/verified/expired/replay, outbox lag/poison, consumer lag/duplicate, Keycloak/JWKS latency/error, rate-limit hits, and audit-chain failure. User ID/email are forbidden as high-cardinality labels.

Traces cover login redirect/callback, token validation, authorization evaluation, step-up, role mutation, event consumption, and outbox publication without raw payloads. Services propagate W3C Trace Context and business correlation ID.

Suggested SLOs: local JWT p95 < 30 ms, authorization p95 < 75 ms, monthly availability 99.9%, revocation propagation p99 < 60 s, and zero successful step-up replays. Alerts cover IdP/JWKS failure, spikes in deny/401 or unknown kid, audit-chain break, poisoned outbox, break-glass use, and revocation backlog.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-029, SPEC-UA-030, SPEC-UA-032`
