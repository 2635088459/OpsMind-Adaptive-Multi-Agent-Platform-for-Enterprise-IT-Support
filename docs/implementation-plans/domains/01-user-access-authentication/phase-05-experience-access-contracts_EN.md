# Phase 05 — Experience Access Contracts

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 05
>
> Specs: `SPEC-UA-020` ～ `SPEC-UA-024`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase Objective

Implement Experience Access Contracts with testable contracts for adjacent domains.

## 2. Scope

Includes domain/application/infrastructure/interface design, migrations, API/event contracts, automated tests, and traceability for this phase.

Excludes custom password/MFA/OIDC protocol implementation, direct writes to Ticket/Workflow/Tool/Memory/Policy state, and cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Primary LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-UA-020` | Employee Portal Authentication Contract | 05-api-contracts, 14-testing-strategy |
| 2 | `SPEC-UA-021` | Ticket Submission Principal Contract | 05-api-contracts, 06-event-contracts |
| 3 | `SPEC-UA-022` | Support Console Authentication Contract | 05-api-contracts, 14-testing-strategy |
| 4 | `SPEC-UA-023` | Approval Center Authentication Contract | 05-api-contracts, 11-security |
| 5 | `SPEC-UA-024` | User Resolution Confirmation Contract | 05-api-contracts, 06-event-contracts |

## 4. Mandatory Constraints

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. Exit Criteria

- All specs have complete bilingual documents, acceptance criteria, test plans, and traceability.
- Corresponding LLD rules have implementation entry points and repeatable tests.
- Security failure paths fail closed without leaking tokens, credentials, or sensitive claims.
