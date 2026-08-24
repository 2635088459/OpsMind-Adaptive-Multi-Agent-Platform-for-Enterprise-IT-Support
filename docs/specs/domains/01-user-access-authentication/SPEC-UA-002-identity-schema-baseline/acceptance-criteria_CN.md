# Acceptance Criteria — SPEC-UA-002

> Domain: User Access And Authentication
>
> Phase: 00 — 工程基础
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `07-data-model, 03-state-machine`
>
> Status: planned

## 验收条目

- 能够完成：身份数据 Schema 基线。
- 未认证、错误 issuer/audience、过期或撤销凭据均 fail closed。
- 重复命令或事件不会产生冲突状态或重复副作用。
- 日志、事件和错误响应不包含原始 token、密码、MFA secret 或不必要 PII。
- 跨域调用只携带最小化且版本化的 identity context。
