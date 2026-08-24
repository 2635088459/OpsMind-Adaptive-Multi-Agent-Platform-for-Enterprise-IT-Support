# SPEC-UA-021 — Ticket Submission Principal Contract

> Domain: User Access And Authentication
>
> Phase: 05 — Experience Access Contracts
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `05-api-contracts, 06-event-contracts`
>
> Status: planned

## 1. Goal

Implement Ticket Submission Principal Contract as an encodable, auditable, recoverable capability with cross-domain contract tests.

## 2. Scope

Includes the required domain/application/infrastructure/interface design, persistence, API/event contracts, tests, and acceptance criteria.

Excludes custom password, MFA, or OIDC protocol implementation and direct modification of state owned by another domain.

## 3. Core Rules

- External Keycloak is the source of truth for credentials and primary authentication.
- Domain 01 emits trusted identity and authorization facts and denies by default.
- Every security decision binds actor, subject, session, assurance, correlation ID, and audit evidence.
