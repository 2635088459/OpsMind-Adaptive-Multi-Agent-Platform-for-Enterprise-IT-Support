# Domain Rules — SPEC-UA-028

> Domain: User Access And Authentication
>
> Phase: 06 — 跨域身份契约
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `06-event-contracts, 08-transaction-and-outbox`
>
> Status: planned

## 规则

- 凭据与 MFA secret 归外部 IdP 所有，01 不复制保存。
- Principal normalization 必须保留 issuer、subject、tenant、session 与 assurance 来源。
- 授权默认拒绝，角色、scope 与资源所有权共同参与决定。
- step-up proof 必须短时有效、绑定 actor/session/action，且不可重放。
