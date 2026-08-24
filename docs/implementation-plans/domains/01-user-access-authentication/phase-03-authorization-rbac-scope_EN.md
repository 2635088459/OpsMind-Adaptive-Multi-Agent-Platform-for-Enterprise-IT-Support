# Phase 03 — Authorization RBAC And Scope

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 03
>
> Specs: `SPEC-UA-011` ～ `SPEC-UA-015`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase Objective

Implement Authorization RBAC And Scope with testable contracts for adjacent domains.

## 2. Scope

Includes domain/application/infrastructure/interface design, migrations, API/event contracts, automated tests, and traceability for this phase.

Excludes custom password/MFA/OIDC protocol implementation, direct writes to Ticket/Workflow/Tool/Memory/Policy state, and cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Primary LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-UA-011` | Role And Permission Model | 01-domain-model, 02-business-invariants |
| 2 | `SPEC-UA-012` | Role Assignment Lifecycle | 03-state-machine, 04-use-cases |
| 3 | `SPEC-UA-013` | Tenant And Support Queue Scope | 02-business-invariants, 11-security |
| 4 | `SPEC-UA-014` | Authorization Context And Decision API | 05-api-contracts, 11-security |
| 5 | `SPEC-UA-015` | Self Service And Resource Ownership | 02-business-invariants, 04-use-cases |

## 4. Mandatory Constraints

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. Exit Criteria

- All specs have complete bilingual documents, acceptance criteria, test plans, and traceability.
- Corresponding LLD rules have implementation entry points and repeatable tests.
- Security failure paths fail closed without leaking tokens, credentials, or sensitive claims.
