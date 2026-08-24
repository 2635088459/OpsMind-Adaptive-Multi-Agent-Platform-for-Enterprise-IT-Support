# Phase 08 — 失败恢复与降级模式

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 08
>
> Specs: `SPEC-UA-032` ～ `SPEC-UA-034`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase 目标

实现失败恢复与降级模式，并以可测试契约接入相邻领域。

## 2. 范围

包含：本 Phase 的 domain/application/infrastructure/interface 设计、migration、API/event contract、自动化测试和 traceability。

不包含：自研密码/MFA/OIDC 协议；直接修改 Ticket、Workflow、Tool、Memory 或 Policy 状态；跨域分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-UA-032` | IdP 故障与 JWKS 降级模式 | 10-failure-handling, 11-security |
| 2 | `SPEC-UA-033` | Session 撤销对账 | 09-concurrency-and-idempotency, 10-failure-handling |
| 3 | `SPEC-UA-034` | 密钥轮换、时钟偏差与重放防御 | 09-concurrency-and-idempotency, 11-security |

## 4. 强制约束

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. 退出条件

- 所有 spec 的中英文文档、验收标准、测试计划与 traceability 完整。
- 对应 LLD 规则有实现入口和可重复测试。
- 安全失败路径采用 fail-closed，且不泄露 token、凭据或敏感 claims。
