# Phase 00 — 工程基础

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 00
>
> Specs: `SPEC-UA-001` ～ `SPEC-UA-003`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase 目标

实现工程基础，并以可测试契约接入相邻领域。

## 2. 范围

包含：本 Phase 的 domain/application/infrastructure/interface 设计、migration、API/event contract、自动化测试和 traceability。

不包含：自研密码/MFA/OIDC 协议；直接修改 Ticket、Workflow、Tool、Memory 或 Policy 状态；跨域分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-UA-001` | 身份模块与包边界 | 13-package-and-class-design, 02-business-invariants |
| 2 | `SPEC-UA-002` | 身份数据 Schema 基线 | 07-data-model, 03-state-machine |
| 3 | `SPEC-UA-003` | 身份 Outbox、Processed Event 与审计基线 | 08-transaction-and-outbox, 09-concurrency-and-idempotency |

## 4. 强制约束

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. 退出条件

- 所有 spec 的中英文文档、验收标准、测试计划与 traceability 完整。
- 对应 LLD 规则有实现入口和可重复测试。
- 安全失败路径采用 fail-closed，且不泄露 token、凭据或敏感 claims。
