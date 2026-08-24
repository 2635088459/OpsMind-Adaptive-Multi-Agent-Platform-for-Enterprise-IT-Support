# Persistence — SPEC-UA-034

> Domain: User Access And Authentication
>
> Phase: 08 — 失败恢复与降级模式
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `09-concurrency-and-idempotency, 11-security`
>
> Status: planned

## 持久化规则

- 只持久化本领域拥有的用户映射、角色分配、session/revocation metadata、step-up challenge、审计、outbox 与 processed-event。
- 敏感列采用加密或不可逆 hash；Token 与 credential 不落库。
- 状态迁移与 audit/outbox 在同一数据库事务提交。
- migration 可前滚、可重复验证，并定义保留与删除策略。
