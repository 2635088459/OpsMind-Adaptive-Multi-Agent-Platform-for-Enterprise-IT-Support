# Domain Rules — SPEC-UA-019

> Domain: User Access And Authentication
>
> Phase: 04 — 认证强度与 Step-Up
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `10-failure-handling, 11-security`
>
> Status: planned

## 规则

- 凭据与 MFA secret 归外部 IdP 所有，01 不复制保存。
- Principal normalization 必须保留 issuer、subject、tenant、session 与 assurance 来源。
- 授权默认拒绝，角色、scope 与资源所有权共同参与决定。
- step-up proof 必须短时有效、绑定 actor/session/action，且不可重放。
