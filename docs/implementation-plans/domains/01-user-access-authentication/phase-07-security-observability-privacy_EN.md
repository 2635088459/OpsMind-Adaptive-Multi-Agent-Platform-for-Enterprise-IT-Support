# Phase 07 — Security Observability And Privacy

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Phase: 07
>
> Specs: `SPEC-UA-029` ～ `SPEC-UA-031`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Plan

## 1. Phase Objective

Implement Security Observability And Privacy with testable contracts for adjacent domains.

## 2. Scope

Includes domain/application/infrastructure/interface design, migrations, API/event contracts, automated tests, and traceability for this phase.

Excludes custom password/MFA/OIDC protocol implementation, direct writes to Ticket/Workflow/Tool/Memory/Policy state, and cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Primary LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-UA-029` | Identity Security Audit Events | 06-event-contracts, 12-observability |
| 2 | `SPEC-UA-030` | Identity Metrics Logs And Traces | 12-observability |
| 3 | `SPEC-UA-031` | Privacy Data Minimization And Retention | 07-data-model, 11-security |

## 4. Mandatory Constraints

- Keycloak is the external IdP; `user-access-authentication-service` never stores credentials or MFA secrets.
- Authorization is deny-by-default and server-enforced.
- Published events use the identity outbox; consumed events use processed-event deduplication.
- Security-sensitive actions include actor, subject, session, assurance level, reason, correlation ID, and audit outcome.

## 5. Exit Criteria

- All specs have complete bilingual documents, acceptance criteria, test plans, and traceability.
- Corresponding LLD rules have implementation entry points and repeatable tests.
- Security failure paths fail closed without leaking tokens, credentials, or sensitive claims.
