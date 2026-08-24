# Persistence — SPEC-UA-007

> Domain: User Access And Authentication
>
> Phase: 01 — OIDC 与 Token 信任
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 05-api-contracts`
>
> Status: planned

## 持久化规则

- 只持久化本领域拥有的用户映射、角色分配、session/revocation metadata、step-up challenge、审计、outbox 与 processed-event。
- 敏感列采用加密或不可逆 hash；Token 与 credential 不落库。
- 状态迁移与 audit/outbox 在同一数据库事务提交。
- migration 可前滚、可重复验证，并定义保留与删除策略。
