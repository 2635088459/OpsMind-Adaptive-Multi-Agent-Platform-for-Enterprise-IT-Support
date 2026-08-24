# 04 用例

| 用例 | Actor | 主流程 | 失败/补偿 |
|---|---|---|---|
| OIDC 登录 | Employee/Support/Admin | 生成 state/nonce/PKCE → Keycloak → callback 校验 → 建立 principal/session metadata | state/nonce/code 不匹配即拒绝并审计 |
| Token 验证 | API Gateway/业务服务 | issuer 路由 → JWKS 验签 → claims 校验 → principal 标准化 | 未知 issuer/audience 或过期 token 返回 401 |
| 用户同步 | IdP event/admin | 按 issuer+subject upsert 最小资料 | 重复 event 去重；冲突进入 quarantine |
| 授予/撤销角色 | Platform Admin | 校验 grantor 委派范围 → 修改 assignment → audit/outbox | 越权或重叠授予返回 403/409 |
| 授权评估 | 02/06/Portal | 构造 action/resource → role/scope/ownership/assurance → immutable decision | 缺失上下文默认 DENY |
| Step-up | 06/Approval Center | 创建 challenge → Keycloak MFA → 验证 proof → 单次消费 | 超时、重放或绑定不符拒绝 |
| Logout/撤销 | 用户/Admin/security event | 撤销本地 session metadata，并请求 IdP end-session/revocation | IdP 不可用则保持本地 revoked 并重试 |
| Workload identity | 内部服务 | client credentials/mTLS 身份 → audience/scope 校验 | 服务不能模拟 human actor |
| Break-glass | 授权管理员 | 强认证 + 双人/06 审批 + 有限时间/范围 | 自动过期并触发高优先级审计 |

所有写用例要求 `Idempotency-Key` 与 `X-Correlation-Id`。查询用例先授权再读取，禁止“先读取后过滤”。用户找不到或无权访问统一返回不泄露存在性的响应。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-005, SPEC-UA-008, SPEC-UA-009, SPEC-UA-012, SPEC-UA-014, SPEC-UA-017, SPEC-UA-019`
