# API Contract — SPEC-UA-031

> Domain: User Access And Authentication
>
> Phase: 07 — 安全、可观测性与隐私
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `07-data-model, 11-security`
>
> Status: planned

## 契约规则

- API 必须使用版本化路径或版本化 media type。
- 所有受保护端点要求经过验证的 principal，并在服务端执行权限与资源 scope 校验。
- 命令要求 correlation ID 和幂等键；安全错误不暴露 token 校验内部细节。
- 内部服务调用使用 workload identity，不接受可伪造的客户端角色 Header。
