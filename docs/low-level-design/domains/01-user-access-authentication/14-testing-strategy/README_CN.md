# 14 测试策略

## 测试层级

- Domain 单元测试：每个聚合构造、合法/非法迁移、不变量、边界时间和 value object normalization。
- Application 测试：fake ports 验证授权默认拒绝、幂等、audit/outbox 原子意图和错误映射。
- Slice 测试：Spring Security filter chain、Controller validation、method security、JPA mapper/repository。
- Integration：Testcontainers PostgreSQL、RabbitMQ 和固定版本 Keycloak realm；运行 Flyway、OIDC code+PKCE、JWT/JWKS rotation、事件发布/消费。
- Contract：01↔Portal/API Gateway、01↔02、01↔06、workload identity；对 request/response/event schema 做 consumer-driven tests。
- E2E：Employee 登录/自助工单、Support queue scope、Approver step-up、Admin role、logout/revoke、服务调用。

## 强制安全用例

错误 issuer/audience/signature/alg/kid、expired/nbf/skew、token substitution/replay、伪造 role header、跨 tenant/水平/垂直越权、session fixation、CSRF/open redirect、step-up action/resource mismatch 和双重消费、JWKS poisoning/refresh storm、IdP/DB/broker 故障、日志/trace/event secret 扫描。

## 质量门禁

所有 domain 分支与安全拒绝路径必须覆盖；mutation test 覆盖关键授权与 step-up 条件；ArchUnit 通过；migration 从空库与上一版本均通过；OpenAPI/AsyncAPI diff 无未批准 breaking change；依赖/SBOM/secret scan 无高危；E2E 和恢复测试通过。测试 fixture 使用合成身份，不使用生产 token/PII。

SPEC-UA-035 提供跨域 harness；SPEC-UA-036 汇总 36 个 spec traceability、测试证据、残余风险、runbook、rollback 和 release sign-off。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-020 through SPEC-UA-027, SPEC-UA-035, SPEC-UA-036`
