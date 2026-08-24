# SPEC-UA-018 — Step Up Proof Verification

> Domain: User Access And Authentication
>
> Phase: 04 — Authentication Assurance And Step Up
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 11-security`
>
> Status: planned

## 1. Goal

Implement Step Up Proof Verification as an encodable, auditable, recoverable capability with cross-domain contract tests.

## 2. Scope

Includes the required domain/application/infrastructure/interface design, persistence, API/event contracts, tests, and acceptance criteria.

Excludes custom password, MFA, or OIDC protocol implementation and direct modification of state owned by another domain.

## 3. Core Rules

- External Keycloak is the source of truth for credentials and primary authentication.
- Domain 01 emits trusted identity and authorization facts and denies by default.
- Every security decision binds actor, subject, session, assurance, correlation ID, and audit evidence.
