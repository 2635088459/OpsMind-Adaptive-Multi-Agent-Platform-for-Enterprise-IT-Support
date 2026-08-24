# SPEC-UA-016 — Authentication Context 与 Assurance Level

> Domain: User Access And Authentication
>
> Phase: 04 — 认证强度与 Step-Up
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 11-security`
>
> Status: planned

## 1. 目标

实现Authentication Context 与 Assurance Level，形成可编码、可审计、可恢复且可进行跨域契约测试的能力。

## 2. 范围

包含本 spec 所需的 domain/application/infrastructure/interface 设计、持久化、API/事件契约、测试和验收标准。

不包含自研密码、MFA 或 OIDC 协议，也不直接修改其他领域拥有的状态。

## 3. 核心规则

- 外部 Keycloak 是 credential 与 primary authentication 的事实来源。
- 01 只发布可信身份与授权事实，授权默认拒绝。
- 所有安全决策绑定 actor、subject、session、assurance、correlation id 与审计证据。
