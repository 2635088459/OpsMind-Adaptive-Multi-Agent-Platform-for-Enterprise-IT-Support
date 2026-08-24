# SPEC-UA-017 — Step Up Challenge Lifecycle

> Domain: User Access And Authentication
>
> Phase: 04 — Authentication Assurance And Step Up
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `03-state-machine, 04-use-cases`
>
> Status: planned

## 1. Goal

Implement Step Up Challenge Lifecycle as an encodable, auditable, recoverable capability with cross-domain contract tests.

## 2. Scope

Includes the required domain/application/infrastructure/interface design, persistence, API/event contracts, tests, and acceptance criteria.

Excludes custom password, MFA, or OIDC protocol implementation and direct modification of state owned by another domain.

## 3. Core Rules

- External Keycloak is the source of truth for credentials and primary authentication.
- Domain 01 emits trusted identity and authorization facts and denies by default.
- Every security decision binds actor, subject, session, assurance, correlation ID, and audit evidence.
