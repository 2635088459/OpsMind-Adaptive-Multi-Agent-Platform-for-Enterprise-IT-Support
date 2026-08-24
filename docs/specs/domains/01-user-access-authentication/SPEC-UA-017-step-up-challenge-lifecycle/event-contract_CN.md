# Event Contract — SPEC-UA-017

> Domain: User Access And Authentication
>
> Phase: 04 — 认证强度与 Step-Up
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `03-state-machine, 04-use-cases`
>
> Status: planned

## 契约规则

- 事件 envelope 包含 event_id、event_type、schema_version、occurred_at、producer、correlation_id 与最小化 payload。
- 身份状态变更通过 outbox 发布；消费者按 event_id 去重。
- 事件不得包含 access/refresh token、密码、MFA secret、session cookie 或完整敏感 claims。
- 兼容性变更必须保持旧消费者可解析；破坏性变更发布新 major version。
