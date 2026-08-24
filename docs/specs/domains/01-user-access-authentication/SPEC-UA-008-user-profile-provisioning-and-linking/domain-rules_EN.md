# Domain Rules — SPEC-UA-008

> Domain: User Access And Authentication
>
> Phase: 02 — User And Session Lifecycle
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 07-data-model`
>
> Status: planned

## Domain Rules

- Credentials and MFA secrets belong to the external IdP and are never copied into domain 01.
- Principal normalization preserves issuer, subject, tenant, session, and assurance provenance.
- Authorization denies by default and evaluates role, scope, and resource ownership together.
- Step-up evidence is short-lived, bound to actor/session/action, and replay resistant.
