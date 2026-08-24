# 10 失败处理

| 故障 | 行为 | 恢复 |
|---|---|---|
| Keycloak 不可用 | 已验证且未过期的低风险 session 可按策略短暂继续；新登录、step-up 和敏感操作 503/fail closed | 健康探测、退避、人工告警 |
| JWKS endpoint 不可用 | 仅使用未过 max-stale 且匹配 issuer 的缓存 key；未知 kid 拒绝 | single-flight refresh/reconcile |
| DB 不可用 | 不创建身份、角色、session、step-up 或授权决定 | 503 retryable；不降级到 allow |
| RabbitMQ 不可用 | 业务事务仍写 outbox；发布积压 | dispatcher retry/poison admin |
| 撤销事件延迟 | 高风险操作执行同步 session/assurance 检查 | reconciliation scan |
| 重复/乱序 IdP event | processed-event 去重；以 upstream version/time 防止旧状态覆盖新状态 | quarantine conflict |
| Token clock skew | 在小且固定窗口校验 nbf/exp | 超限拒绝并记录指标 |
| Step-up callback 丢失 | challenge 保持 PENDING 到期 | 用户重新发起；旧 nonce 不复用 |
| Audit chain mismatch | 停止高权限管理写入并报警 | 只读调查和受控 repair |

降级模式分 `NORMAL`, `READ_ONLY_IDENTITY`, `CACHED_VALIDATION_ONLY`, `FAIL_CLOSED`。任何降级都不能扩大权限；错误响应不区分“用户不存在”和“无权查看”。Recovery 操作必须有 admin 权限、原因、幂等键和完整审计。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-006, SPEC-UA-009, SPEC-UA-019, SPEC-UA-032, SPEC-UA-033, SPEC-UA-034`
