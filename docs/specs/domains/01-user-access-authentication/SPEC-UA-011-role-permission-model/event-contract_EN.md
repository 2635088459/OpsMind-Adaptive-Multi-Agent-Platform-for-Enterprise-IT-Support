# Event Contract — SPEC-UA-011

> Domain: User Access And Authentication
>
> Phase: 03 — Authorization RBAC And Scope
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `01-domain-model, 02-business-invariants`
>
> Status: planned

## Event Contract

- Event envelopes include event_id, event_type, schema_version, occurred_at, producer, correlation_id, and a minimized payload.
- Identity state changes publish through the outbox and consumers deduplicate by event_id.
- Events never contain access/refresh tokens, passwords, MFA secrets, session cookies, or full sensitive claims.
- Compatible changes remain readable by old consumers; breaking changes use a new major version.
