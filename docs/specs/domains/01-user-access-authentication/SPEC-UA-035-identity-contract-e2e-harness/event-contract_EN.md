# Event Contract — SPEC-UA-035

> Domain: User Access And Authentication
>
> Phase: 09 — Final Verification And Release
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `14-testing-strategy`
>
> Status: planned

## Event Contract

- Event envelopes include event_id, event_type, schema_version, occurred_at, producer, correlation_id, and a minimized payload.
- Identity state changes publish through the outbox and consumers deduplicate by event_id.
- Events never contain access/refresh tokens, passwords, MFA secrets, session cookies, or full sensitive claims.
- Compatible changes remain readable by old consumers; breaking changes use a new major version.
