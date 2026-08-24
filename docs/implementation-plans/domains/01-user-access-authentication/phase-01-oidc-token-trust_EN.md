# Phase 01 — OIDC And Token Trust

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 01
>
> Specs: `SPEC-UA-004` ～ `SPEC-UA-007`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase Objective

Implement OIDC And Token Trust with testable contracts for adjacent domains.

## 2. Scope

Includes domain/application/infrastructure/interface design, migrations, API/event contracts, automated tests, and traceability for this phase.

Excludes custom password/MFA/OIDC protocol implementation, direct writes to Ticket/Workflow/Tool/Memory/Policy state, and cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Primary LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-UA-004` | OIDC Provider Discovery And Configuration | 05-api-contracts, 11-security |
| 2 | `SPEC-UA-005` | Authorization Code PKCE Login Callback | 04-use-cases, 05-api-contracts |
| 3 | `SPEC-UA-006` | JWT Validation And JWKS Rotation | 11-security, 10-failure-handling |
| 4 | `SPEC-UA-007` | Principal Claims Normalization | 01-domain-model, 05-api-contracts |

## 4. Mandatory Constraints

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. Exit Criteria

- All specs have complete bilingual documents, acceptance criteria, test plans, and traceability.
- Corresponding LLD rules have implementation entry points and repeatable tests.
- Security failure paths fail closed without leaking tokens, credentials, or sensitive claims.
