# 03 状态机

## `UserIdentity`

```text
ACTIVE ──disable──> DISABLED ──enable──> ACTIVE
   └────deprovision───────────> DEPROVISIONED (final)
DISABLED ──deprovision────────> DEPROVISIONED
```

`DEPROVISIONED` 不可恢复；重新入职生成新的映射或通过显式 re-link 流程关联，不能静默复活旧权限。

## `RoleAssignment`

```text
PENDING ──activate(validFrom)──> ACTIVE ──revoke──> REVOKED
   └────────cancel─────────────> CANCELLED
ACTIVE ──validUntil reached────> EXPIRED
```

终态为 `REVOKED`, `EXPIRED`, `CANCELLED`。同一用户、role、scope 的重叠 ACTIVE assignment 必须由唯一约束/事务检查阻止。

## `UserSession`

```text
ACTIVE ──expiry──> EXPIRED
ACTIVE ──logout/admin revoke──> REVOKED
ACTIVE ──security signal──────> COMPROMISED
ACTIVE ──normal termination───> TERMINATED
```

所有非 ACTIVE 状态为终态。刷新 token 只更新受控元数据或创建后继 session，不能解除撤销。

## `StepUpChallenge`

```text
REQUESTED → PENDING → VERIFIED → CONSUMED
                 ├──attempt limit→ FAILED
                 ├──timeout──────> EXPIRED
                 └──cancel───────> CANCELLED
```

`VERIFIED → CONSUMED` 通过条件更新 `WHERE status='VERIFIED' AND expires_at>now()`；并发只有一个消费者成功。Proof 的 action/resource 不匹配时保持原状态并记录拒绝审计。

## `ServiceIdentity`

`ACTIVE → DISABLED → RETIRED`；`RETIRED` 为终态。超过 `validUntil` 在授权时按无效处理，并由 reconciliation 转为 `RETIRED`。

每次合法迁移同事务写 domain audit 和 outbox。非法迁移返回稳定错误码，不泄露对象是否存在给无权调用者。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-002, SPEC-UA-009, SPEC-UA-012, SPEC-UA-017`
