# 09 并发与幂等

- 聚合使用 `version` 乐观锁；API 将版本映射为 `ETag/If-Match` 或请求字段。冲突返回 409，不自动覆盖管理员变更。
- 所有命令保存 `(tenantId, operation, idempotencyKey, requestHash, responseRef)`；相同 key+hash 返回原结果，相同 key+不同 hash 返回 `IDEMPOTENCY_CONFLICT`。
- 用户 provisioning 依赖 UNIQUE `(tenant,issuer,subject)`；并发首次登录只创建一个映射。
- Role assignment 使用 partial unique active key，同时事务内检查有效期重叠。
- Step-up 消费用原子条件更新和唯一 `proofIdHash`；最多一个请求从 VERIFIED 进入 CONSUMED。
- Session revoke 幂等：重复撤销返回已有终态，不能更新原始 revoke actor/reason。
- JWKS refresh 采用 single-flight；未知 `kid` 触发一次受限刷新，不能形成攻击者控制的刷新风暴。
- 缓存 key 必须包含 tenant、issuer、subject、role/profile version；role revoke/user disable 事件使缓存失效。
- Event 用 `(consumer,eventId)` 去重；worker lease/heartbeat 防止 recovery 与正常 worker 同时处理。

时间判断使用服务端 UTC clock；测试注入 `Clock`。允许的 clock skew 配置有上限，不能通过扩大 skew 接受过期 token。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-006, SPEC-UA-009, SPEC-UA-012, SPEC-UA-018, SPEC-UA-033, SPEC-UA-034`
