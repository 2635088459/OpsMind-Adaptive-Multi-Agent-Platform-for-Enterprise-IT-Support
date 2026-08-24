# 12 可观测性

所有日志为结构化 JSON，含 `timestamp`, `service`, `environment`, `traceId`, `spanId`, `correlationId`, `tenantId`, `actorRef`, `subjectRef`, `action`, `outcome`, `reasonCode`；引用使用 opaque ID/hash。禁止 token、cookie、Authorization Header、密码、MFA/nonce/proof、原始 email/device/IP（必要时脱敏）。

关键指标：认证成功/失败（issuer/client/reason）、JWT validation latency/cache hit/unknown kid、active/revoked session、authorization allow/deny/step-up、role grant/revoke、step-up requested/verified/expired/replay、outbox lag/poison、consumer lag/duplicate、Keycloak/JWKS latency/error、rate-limit hit、audit-chain failure。禁止以 user ID/email 作为高基数 label。

Trace 覆盖 login redirect/callback、token validation、authorization evaluation、step-up、role mutation、event consume/outbox publish；不记录 raw payload。跨服务传播 W3C Trace Context 和业务 correlation ID。

SLO 建议：本地 JWT p95 < 30ms、授权评估 p95 < 75ms、月可用性 99.9%、撤销事件传播 p99 < 60s、step-up replay 成功数必须为 0。告警包括 IdP/JWKS 故障、deny/401 激增、未知 kid 激增、审计链破坏、outbox poison、break-glass 使用和撤销积压。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-029, SPEC-UA-030, SPEC-UA-032`
