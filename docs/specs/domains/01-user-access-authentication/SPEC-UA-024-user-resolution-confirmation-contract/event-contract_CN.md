# Event Contract — SPEC-UA-024

> Domain: User Access And Authentication
>
> Phase: 05 — 体验层访问契约
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 06-event-contracts`
>
> Status: planned

## 契约规则

- 事件 envelope 包含 event_id、event_type、schema_version、occurred_at、producer、correlation_id 与最小化 payload。
- 身份状态变更通过 outbox 发布；消费者按 event_id 去重。
- 事件不得包含 access/refresh token、密码、MFA secret、session cookie 或完整敏感 claims。
- 兼容性变更必须保持旧消费者可解析；破坏性变更发布新 major version。
