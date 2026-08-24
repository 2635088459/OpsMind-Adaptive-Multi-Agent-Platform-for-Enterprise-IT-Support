# Test Plan — SPEC-UA-022

> Domain: User Access And Authentication
>
> Phase: 05 — Experience Access Contracts
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 14-testing-strategy`
>
> Status: planned

## Test Plan

- Unit tests cover rules, state transitions, and denial paths.
- Integration tests use PostgreSQL, RabbitMQ, and an isolated Keycloak test instance/container.
- Contract tests cover issuer/audience, claims, roles/scopes, step-up, and error envelopes.
- Security tests cover token substitution, replay, clock skew, key rotation, session revocation, and sensitive-data leakage.
- E2E tests cover Employee, Support Agent, Approver, Administrator, and workload identity.
