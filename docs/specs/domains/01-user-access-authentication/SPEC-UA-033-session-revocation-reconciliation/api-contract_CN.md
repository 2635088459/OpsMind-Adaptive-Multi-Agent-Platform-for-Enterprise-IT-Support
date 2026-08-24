# API Contract — SPEC-UA-033

> Domain: User Access And Authentication
>
> Phase: 08 — 失败恢复与降级模式
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `09-concurrency-and-idempotency, 10-failure-handling`
>
> Status: planned

## 契约规则

- API 必须使用版本化路径或版本化 media type。
- 所有受保护端点要求经过验证的 principal，并在服务端执行权限与资源 scope 校验。
- 命令要求 correlation ID 和幂等键；安全错误不暴露 token 校验内部细节。
- 内部服务调用使用 workload identity，不接受可伪造的客户端角色 Header。
