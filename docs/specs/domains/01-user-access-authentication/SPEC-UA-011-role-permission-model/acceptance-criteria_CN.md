# Acceptance Criteria — SPEC-UA-011

> Domain: User Access And Authentication
>
> Phase: 03 — 授权、RBAC 与 Scope
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 02-business-invariants`
>
> Status: planned

## 验收条目

- 能够完成：角色与权限模型。
- 未认证、错误 issuer/audience、过期或撤销凭据均 fail closed。
- 重复命令或事件不会产生冲突状态或重复副作用。
- 日志、事件和错误响应不包含原始 token、密码、MFA secret 或不必要 PII。
- 跨域调用只携带最小化且版本化的 identity context。
