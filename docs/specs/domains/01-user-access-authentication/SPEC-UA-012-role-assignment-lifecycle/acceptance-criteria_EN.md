# Acceptance Criteria — SPEC-UA-012

> Domain: User Access And Authentication
>
> Phase: 03 — Authorization RBAC And Scope
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `03-state-machine, 04-use-cases`
>
> Status: planned

## Acceptance Criteria

- The system completes: Role Assignment Lifecycle.
- Unauthenticated, wrong-issuer/audience, expired, or revoked credentials fail closed.
- Duplicate commands or events produce no conflicting state or repeated side effects.
- Logs, events, and errors contain no raw tokens, passwords, MFA secrets, or unnecessary PII.
- Cross-domain calls carry only minimized, versioned identity context.
