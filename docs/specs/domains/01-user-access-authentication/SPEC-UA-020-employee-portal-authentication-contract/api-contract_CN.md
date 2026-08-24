# API Contract — SPEC-UA-020

> Domain: User Access And Authentication
>
> Phase: 05 — 体验层访问契约
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 14-testing-strategy`
>
> Status: planned

## 契约规则

- API 必须使用版本化路径或版本化 media type。
- 所有受保护端点要求经过验证的 principal，并在服务端执行权限与资源 scope 校验。
- 命令要求 correlation ID 和幂等键；安全错误不暴露 token 校验内部细节。
- 内部服务调用使用 workload identity，不接受可伪造的客户端角色 Header。
