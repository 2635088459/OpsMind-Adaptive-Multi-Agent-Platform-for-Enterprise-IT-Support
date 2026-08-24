# Domain Rules — SPEC-UA-032

> Domain: User Access And Authentication
>
> Phase: 08 — Failure Recovery And Degraded Mode
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `10-failure-handling, 11-security`
>
> Status: planned

## Domain Rules

- Credentials and MFA secrets belong to the external IdP and are never copied into domain 01.
- Principal normalization preserves issuer, subject, tenant, session, and assurance provenance.
- Authorization denies by default and evaluates role, scope, and resource ownership together.
- Step-up evidence is short-lived, bound to actor/session/action, and replay resistant.
