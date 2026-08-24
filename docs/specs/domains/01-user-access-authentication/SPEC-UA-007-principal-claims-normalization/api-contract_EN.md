# API Contract — SPEC-UA-007

> Domain: User Access And Authentication
>
> Phase: 01 — OIDC And Token Trust
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 05-api-contracts`
>
> Status: planned

## API Contract

- APIs use versioned paths or media types.
- Protected endpoints require a verified principal and server-side permission/resource-scope checks.
- Commands require a correlation ID and idempotency key; security errors do not reveal token-validation internals.
- Internal service calls use workload identity and reject client-forged role headers.
