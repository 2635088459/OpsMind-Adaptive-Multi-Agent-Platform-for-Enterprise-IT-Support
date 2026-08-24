# API Contract — SPEC-UA-012

> Domain: User Access And Authentication
>
> Phase: 03 — Authorization RBAC And Scope
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `03-state-machine, 04-use-cases`
>
> Status: planned

## API Contract

- APIs use versioned paths or media types.
- Protected endpoints require a verified principal and server-side permission/resource-scope checks.
- Commands require a correlation ID and idempotency key; security errors do not reveal token-validation internals.
- Internal service calls use workload identity and reject client-forged role headers.
