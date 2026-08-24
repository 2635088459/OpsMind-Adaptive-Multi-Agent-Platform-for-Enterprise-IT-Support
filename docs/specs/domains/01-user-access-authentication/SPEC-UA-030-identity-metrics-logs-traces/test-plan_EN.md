# Test Plan — SPEC-UA-030

> Domain: User Access And Authentication
>
> Phase: 07 — Security Observability And Privacy
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `12-observability`
>
> Status: planned

## Test Plan

- Unit tests cover rules, state transitions, and denial paths.
- Integration tests use PostgreSQL, RabbitMQ, and an isolated Keycloak test instance/container.
- Contract tests cover issuer/audience, claims, roles/scopes, step-up, and error envelopes.
- Security tests cover token substitution, replay, clock skew, key rotation, session revocation, and sensitive-data leakage.
- E2E tests cover Employee, Support Agent, Approver, Administrator, and workload identity.
