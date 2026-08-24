# Phase 05 — 体验层访问契约

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 05
>
> Specs: `SPEC-UA-020` ～ `SPEC-UA-024`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase 目标

实现体验层访问契约，并以可测试契约接入相邻领域。

## 2. 范围

包含：本 Phase 的 domain/application/infrastructure/interface 设计、migration、API/event contract、自动化测试和 traceability。

不包含：自研密码/MFA/OIDC 协议；直接修改 Ticket、Workflow、Tool、Memory 或 Policy 状态；跨域分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-UA-020` | Employee Portal 认证契约 | 05-api-contracts, 14-testing-strategy |
| 2 | `SPEC-UA-021` | 工单提交 Principal 契约 | 05-api-contracts, 06-event-contracts |
| 3 | `SPEC-UA-022` | Support Console 认证契约 | 05-api-contracts, 14-testing-strategy |
| 4 | `SPEC-UA-023` | Approval Center 认证契约 | 05-api-contracts, 11-security |
| 5 | `SPEC-UA-024` | 用户解决确认认证契约 | 05-api-contracts, 06-event-contracts |

## 4. 强制约束

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. 退出条件

- 所有 spec 的中英文文档、验收标准、测试计划与 traceability 完整。
- 对应 LLD 规则有实现入口和可重复测试。
- 安全失败路径采用 fail-closed，且不泄露 token、凭据或敏感 claims。
