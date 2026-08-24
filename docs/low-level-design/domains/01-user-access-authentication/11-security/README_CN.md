# 11 安全

## Token 与协议

- 使用 Authorization Code + PKCE；state、nonce 一次性且短时；禁止 implicit/password grant。
- 每个 issuer 配置固定 discovery URL、允许算法、audience 和 token type；拒绝 `alg=none`、算法混淆和任意 `jku/x5u`。
- 浏览器采用 Secure、HttpOnly、SameSite cookie 和 CSRF 防护；回调 URL 精确 allowlist。
- Workload 使用 client credentials 或 mTLS，独立 audience/scope，不能模拟 human `sub`。

## 授权与数据

- Controller、application use case 和 repository query 三层执行 tenant/scope 约束；RAG/SQL 在查询阶段过滤，禁止读取后再让 LLM 判断。
- 用户文本、Agent 输出和自报授权均是不可信数据。01 只接受验证 token 和服务端映射。
- Step-up proof 采用 opaque handle/hash，绑定 action/resource/session，单次消费；06 仍负责审批与职责分离。
- Secrets 进入 secret manager/environment injection；禁止写配置库、日志、trace、event、ticket、memory 或 prompt。

## 防攻击与隐私

- 对登录、callback、introspection、step-up 和 admin API 分主体/IP/client 限流；异常触发安全事件。
- 输入 schema validation、防 mass assignment、参数化 SQL、固定 outbound host，避免 injection/SSRF。
- PII 分级、字段级脱敏/加密、最小返回、保留/删除；审计访问本身要审计。
- Break-glass 要强认证、06 审批/双人控制、有限 scope/time、不可关闭审计。

威胁模型必须覆盖 token substitution/replay/theft、session fixation、CSRF、open redirect、JWKS poisoning、confused deputy、horizontal/vertical privilege escalation、tenant escape、PII leakage 和 malicious IdP/event。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-004, SPEC-UA-005, SPEC-UA-006, SPEC-UA-010, SPEC-UA-013, SPEC-UA-015, SPEC-UA-016, SPEC-UA-018, SPEC-UA-019, SPEC-UA-031, SPEC-UA-034`
