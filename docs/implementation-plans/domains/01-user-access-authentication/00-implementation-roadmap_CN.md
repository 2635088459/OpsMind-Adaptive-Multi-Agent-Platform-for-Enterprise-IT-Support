# 01 用户访问与认证实施路线图

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Roadmap

## 1. 总目标

把 `01-user-access-authentication` 从占位领域落成可实现、可验证的身份安全边界。外部 Keycloak 负责凭据、OIDC/OAuth2 协议和 MFA；本服务负责用户映射、会话/撤销、RBAC/scope、认证强度、step-up 证明、审计以及面向 02/03/04/05/06 的可信身份契约。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| 00 | 工程基础 | `SPEC-UA-001` ～ `SPEC-UA-003` | 完成 工程基础 的闭环。 |
| 01 | OIDC 与 Token 信任 | `SPEC-UA-004` ～ `SPEC-UA-007` | 完成 OIDC 与 Token 信任 的闭环。 |
| 02 | 用户与会话生命周期 | `SPEC-UA-008` ～ `SPEC-UA-010` | 完成 用户与会话生命周期 的闭环。 |
| 03 | 授权、RBAC 与 Scope | `SPEC-UA-011` ～ `SPEC-UA-015` | 完成 授权、RBAC 与 Scope 的闭环。 |
| 04 | 认证强度与 Step-Up | `SPEC-UA-016` ～ `SPEC-UA-019` | 完成 认证强度与 Step-Up 的闭环。 |
| 05 | 体验层访问契约 | `SPEC-UA-020` ～ `SPEC-UA-024` | 完成 体验层访问契约 的闭环。 |
| 06 | 跨域身份契约 | `SPEC-UA-025` ～ `SPEC-UA-028` | 完成 跨域身份契约 的闭环。 |
| 07 | 安全、可观测性与隐私 | `SPEC-UA-029` ～ `SPEC-UA-031` | 完成 安全、可观测性与隐私 的闭环。 |
| 08 | 失败恢复与降级模式 | `SPEC-UA-032` ～ `SPEC-UA-034` | 完成 失败恢复与降级模式 的闭环。 |
| 09 | 最终验证与发布 | `SPEC-UA-035` ～ `SPEC-UA-036` | 完成 最终验证与发布 的闭环。 |

## 3. 边界原则

- 不存储用户密码、MFA secret 或 IdP 私钥。
- 浏览器和服务 Token 必须校验 issuer、audience、signature、expiry 与 token type。
- 授权默认拒绝；不能把前端隐藏按钮当作授权。
- 01 只输出可信 principal、authentication context 和 authorization facts，不拥有 Ticket、Workflow、Tool、Memory 或 Policy 状态。
- 高风险审批必须使用可验证且短时有效的 step-up 证明。
- 状态变更同事务写 audit/outbox；事件消费使用 processed-event 去重。
