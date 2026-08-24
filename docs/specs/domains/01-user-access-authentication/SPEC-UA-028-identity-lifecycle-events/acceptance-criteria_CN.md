# Acceptance Criteria — SPEC-UA-028

> Domain: User Access And Authentication
>
> Phase: 06 — 跨域身份契约
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `06-event-contracts, 08-transaction-and-outbox`
>
> Status: planned

## 验收条目

- 能够完成：身份生命周期事件。
- 未认证、错误 issuer/audience、过期或撤销凭据均 fail closed。
- 重复命令或事件不会产生冲突状态或重复副作用。
- 日志、事件和错误响应不包含原始 token、密码、MFA secret 或不必要 PII。
- 跨域调用只携带最小化且版本化的 identity context。
