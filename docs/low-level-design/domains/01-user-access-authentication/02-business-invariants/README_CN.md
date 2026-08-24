# 02 业务不变量

## 身份与信任

1. 只有白名单内的 HTTPS issuer 才能创建 principal；签名、`iss`、`aud`、`exp`、`nbf` 与 token type 校验都是强制的。
2. `(tenantId, issuer, subject)` 是稳定的用户身份。用户名、邮箱、显示名和用户文本都不授予任何权限。
3. 密码、MFA 密钥、原始 token、会话 cookie 和 IdP 私钥禁止出现在存储、日志和事件中。
4. `DISABLED`/`DEPROVISIONED` 用户、被禁用的 workload、以及已撤销/已过期的 session，一律 fail closed。

## 授权

5. 默认拒绝。Allow 需要可信 principal、有效角色分配、资源范围、归属规则与 assurance 要求这五者的交集同时成立。
6. `SELF` 只允许访问映射到 token subject 自身的资源；请求体里的 `userId` 不能扩大访问范围。
7. 通过客户端 header 传入的角色、tenant、subject 和 step-up 标记一律不可信；只有已验证的 token claim 和服务端映射才能产生它们。
8. 域 01 决定身份层面的访问，域 06 决定风险、审批与业务治理。01 的 `ALLOW` 从不等同于工具执行权限。
9. 角色授予方不能超出自身的授予范围进行委派。域 01 提供职责分离所需的身份事实，域 06 做出治理决策。

## Step-up 与会话

10. Step-up 证明绑定 issuer、subject、session、action、resource、assurance 与过期时间，且只能使用一次。
11. 验证、消费与重放拒绝是原子操作；已过期的 challenge 永远不能再回到 `VERIFIED`。
12. 撤销以最终一致性传播。当撤销状态未知、JWKS 无法安全刷新、或 IdP 信任失败时，敏感操作一律拒绝。

## 审计与隐私

13. 角色变更、会话撤销、step-up、break-glass 与授权决策都要记录 actor、subject、reason、correlation、变更前后状态与结果。
14. PII 按用途最小化。用户档案删除后，去标识化的安全审计记录可以保留，但不能借此还原出原始身份。
15. 重试与重复事件绝不产生重复角色、重复消费 challenge 或相互冲突的决策。

---

> Domain: `01-user-access-authentication`
> Service: `user-access-authentication-service`
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
> Status: Detailed LLD
> Spec mapping: `SPEC-UA-001, SPEC-UA-006, SPEC-UA-011, SPEC-UA-013, SPEC-UA-015, SPEC-UA-018, SPEC-UA-019`
