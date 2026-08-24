# Event Contract — SPEC-UA-029

> Domain: User Access And Authentication
>
> Phase: 07 — Security Observability And Privacy
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `06-event-contracts, 12-observability`
>
> Status: planned

## Event Contract

- Event envelopes include event_id, event_type, schema_version, occurred_at, producer, correlation_id, and a minimized payload.
- Identity state changes publish through the outbox and consumers deduplicate by event_id.
- Events never contain access/refresh tokens, passwords, MFA secrets, session cookies, or full sensitive claims.
- Compatible changes remain readable by old consumers; breaking changes use a new major version.
