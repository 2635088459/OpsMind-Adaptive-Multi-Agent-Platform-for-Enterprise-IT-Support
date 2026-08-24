# Test Plan — SPEC-UA-004

> Domain: User Access And Authentication
>
> Phase: 01 — OIDC 与 Token 信任
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 11-security`
>
> Status: planned

## 测试范围

- 单元测试覆盖规则、状态迁移和拒绝路径。
- 集成测试使用 PostgreSQL、RabbitMQ 与隔离的 Keycloak 测试实例/容器。
- 契约测试覆盖 issuer/audience、claims、角色/scope、step-up 与错误 envelope。
- 安全测试覆盖 token substitution、replay、clock skew、key rotation、session revocation 和敏感数据泄漏。
- E2E 测试覆盖 Employee、Support Agent、Approver、Administrator 与 workload identity。
