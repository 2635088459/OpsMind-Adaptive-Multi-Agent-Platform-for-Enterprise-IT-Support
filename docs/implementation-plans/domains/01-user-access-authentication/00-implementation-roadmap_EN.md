# 01 User Access And Authentication Implementation Roadmap

> Domain: User Access And Authentication
>
> Service: `user-access-authentication-service`
>
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`
>
> Document status: Implementation Roadmap

## 1. Goal

Turn `01-user-access-authentication` into an implementable and verifiable identity security boundary. External Keycloak owns credentials, OIDC/OAuth2 protocols, and MFA; this service owns user mapping, session/revocation state, RBAC/scopes, authentication assurance, step-up evidence, audit, and trusted identity contracts for domains 02/03/04/05/06.

## 2. Phase Overview

| Phase | Name | Specs | Objective |
|---|---|---|---|
| 00 | Engineering Foundation | `SPEC-UA-001` ～ `SPEC-UA-003` | Close the Engineering Foundation capability slice. |
| 01 | OIDC And Token Trust | `SPEC-UA-004` ～ `SPEC-UA-007` | Close the OIDC And Token Trust capability slice. |
| 02 | User And Session Lifecycle | `SPEC-UA-008` ～ `SPEC-UA-010` | Close the User And Session Lifecycle capability slice. |
| 03 | Authorization RBAC And Scope | `SPEC-UA-011` ～ `SPEC-UA-015` | Close the Authorization RBAC And Scope capability slice. |
| 04 | Authentication Assurance And Step Up | `SPEC-UA-016` ～ `SPEC-UA-019` | Close the Authentication Assurance And Step Up capability slice. |
| 05 | Experience Access Contracts | `SPEC-UA-020` ～ `SPEC-UA-024` | Close the Experience Access Contracts capability slice. |
| 06 | Cross Domain Identity Contracts | `SPEC-UA-025` ～ `SPEC-UA-028` | Close the Cross Domain Identity Contracts capability slice. |
| 07 | Security Observability And Privacy | `SPEC-UA-029` ～ `SPEC-UA-031` | Close the Security Observability And Privacy capability slice. |
| 08 | Failure Recovery And Degraded Mode | `SPEC-UA-032` ～ `SPEC-UA-034` | Close the Failure Recovery And Degraded Mode capability slice. |
| 09 | Final Verification And Release | `SPEC-UA-035` ～ `SPEC-UA-036` | Close the Final Verification And Release capability slice. |

## 3. Boundary Principles

- Never store user passwords, MFA secrets, or IdP private keys.
- Validate issuer, audience, signature, expiry, and token type for browser and service tokens.
- Authorization is deny-by-default; hiding a UI control is not authorization.
- 01 emits trusted principal, authentication context, and authorization facts but owns no Ticket, Workflow, Tool, Memory, or Policy state.
- High-risk approval requires verifiable and short-lived step-up evidence.
- Write audit/outbox atomically with state changes and deduplicate consumed events.
