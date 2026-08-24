# Test Plan — SPEC-UA-018

> Domain: User Access And Authentication
>
> Phase: 04 — Authentication Assurance And Step Up
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 11-security`
>
> Status: planned

## Test Plan

- Unit tests cover rules, state transitions, and denial paths.
- Integration tests use PostgreSQL, RabbitMQ, and an isolated Keycloak test instance/container.
- Contract tests cover issuer/audience, claims, roles/scopes, step-up, and error envelopes.
- Security tests cover token substitution, replay, clock skew, key rotation, session revocation, and sensitive-data leakage.
- E2E tests cover Employee, Support Agent, Approver, Administrator, and workload identity.
