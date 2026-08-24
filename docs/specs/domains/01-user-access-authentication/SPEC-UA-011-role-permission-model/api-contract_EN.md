# API Contract — SPEC-UA-011

> Domain: User Access And Authentication
>
> Phase: 03 — Authorization RBAC And Scope
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 02-business-invariants`
>
> Status: planned

## API Contract

- APIs use versioned paths or media types.
- Protected endpoints require a verified principal and server-side permission/resource-scope checks.
- Commands require a correlation ID and idempotency key; security errors do not reveal token-validation internals.
- Internal service calls use workload identity and reject client-forged role headers.
