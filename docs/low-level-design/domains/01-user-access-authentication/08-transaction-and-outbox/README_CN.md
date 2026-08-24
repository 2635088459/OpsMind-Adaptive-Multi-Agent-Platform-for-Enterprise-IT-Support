# 08 事务与 Outbox

单个聚合状态、审计记录和 outbox 事件在同一 PostgreSQL 事务提交。禁止在数据库事务内同步调用 Keycloak、RabbitMQ 或其他领域。

| 命令 | 原子写入 |
|---|---|
| provision/disable user | user identity + audit + user event |
| assign/revoke role | role assignment + audit + role event |
| revoke session | session + audit + revocation event |
| verify/consume step-up | conditional challenge update + audit + assurance event |
| disable service identity | service identity + audit + event |

外部 Keycloak 操作采用本地 intent/outbox → adapter retry → reconciliation，不做 2PC。Logout 时先本地 fail-closed 撤销，再尽力通知 IdP。Outbox dispatcher 使用 `FOR UPDATE SKIP LOCKED` claim 批次、指数退避和最大尝试；超过阈值进入 `POISONED`，只能经授权管理接口 requeue。发布成功后按 event ID 幂等标记。

消费事务先插入 `processed_events`，再修改聚合并写本域 outbox；唯一键冲突表示已处理。Payload hash 不同但 event ID 相同进入 quarantine。审计/outbox 写失败必须回滚业务变化。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-003, SPEC-UA-009, SPEC-UA-012, SPEC-UA-017, SPEC-UA-028`
