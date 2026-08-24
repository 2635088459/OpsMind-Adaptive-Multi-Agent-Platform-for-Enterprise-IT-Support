# 05 API 契约

基路径 `/internal/identity/v1`；浏览器登录端点通过 BFF/API Gateway 暴露。错误 envelope：`code`, `message`, `correlationId`, `retryable`，不得返回 token 校验内部信息。

| Method/Path | 权限 | 请求/响应关键字段 |
|---|---|---|
| `GET /oauth2/authorization/{provider}` | anonymous | 302；生成 state、nonce、PKCE |
| `GET /login/oauth2/code/{provider}` | callback | 校验后建立安全 HttpOnly/SameSite cookie 或返回一次性 exchange code |
| `POST /sessions/logout` | authenticated | session derived from principal；204 |
| `POST /tokens/introspect-context` | trusted workload | token 不落日志；返回 normalized principal、assurance、session status |
| `GET /users/me` | human | 返回最小 profile 与有效角色/scope |
| `PUT /users/{id}/status` | `identity:user:admin` | `status`, `reason`; 资源版本/幂等键 |
| `POST /role-assignments` | `identity:role:grant` | userId, roleCode, scopeType/id, validFrom/until, reason |
| `DELETE /role-assignments/{id}` | `identity:role:revoke` | reason；204/idempotent |
| `POST /authorization-decisions` | trusted workload | principalRef, action, resource, ownershipContext, requiredAssurance |
| `POST /step-up/challenges` | authenticated | action, resource, requiredAcr/amr；返回 challengeId, redirect, expiresAt |
| `POST /step-up/challenges/{id}/verify` | callback/workload | IdP evidence；返回 opaque one-time proof handle |
| `POST /step-up/proofs/{handle}/consume` | trusted workload | action/resource/correlation；返回 verified assurance |
| `POST /sessions/{id}/revoke` | self/admin | reason；禁止普通用户撤销他人 session |

HTTP：401=未认证/无效凭据，403=身份可信但无权限，404=有权查询时对象不存在，409=幂等冲突/非法状态，422=结构合法但语义无效，429=速率限制，503=无法安全验证身份。内部调用必须使用 workload token，不能用 `X-User-Role` 等 Header 模拟身份。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-004, SPEC-UA-005, SPEC-UA-006, SPEC-UA-014, SPEC-UA-018, SPEC-UA-020, SPEC-UA-022, SPEC-UA-023`
