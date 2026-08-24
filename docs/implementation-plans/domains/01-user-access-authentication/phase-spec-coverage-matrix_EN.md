# 01 User Access And Authentication Phase / Spec Coverage Matrix

## Goal

Confirm that 10 phases and 36 specs cover all 14 LLD slices and close identity contracts for the experience layer, Ticket Workflow, Policy Governance, and internal services.

## Phase / Spec Overview

| Phase | Specs | Closure Objective |
|---|---|---|
| 00 Engineering Foundation | `SPEC-UA-001` ～ `SPEC-UA-003` | Close Engineering Foundation. |
| 01 OIDC And Token Trust | `SPEC-UA-004` ～ `SPEC-UA-007` | Close OIDC And Token Trust. |
| 02 User And Session Lifecycle | `SPEC-UA-008` ～ `SPEC-UA-010` | Close User And Session Lifecycle. |
| 03 Authorization RBAC And Scope | `SPEC-UA-011` ～ `SPEC-UA-015` | Close Authorization RBAC And Scope. |
| 04 Authentication Assurance And Step Up | `SPEC-UA-016` ～ `SPEC-UA-019` | Close Authentication Assurance And Step Up. |
| 05 Experience Access Contracts | `SPEC-UA-020` ～ `SPEC-UA-024` | Close Experience Access Contracts. |
| 06 Cross Domain Identity Contracts | `SPEC-UA-025` ～ `SPEC-UA-028` | Close Cross Domain Identity Contracts. |
| 07 Security Observability And Privacy | `SPEC-UA-029` ～ `SPEC-UA-031` | Close Security Observability And Privacy. |
| 08 Failure Recovery And Degraded Mode | `SPEC-UA-032` ～ `SPEC-UA-034` | Close Failure Recovery And Degraded Mode. |
| 09 Final Verification And Release | `SPEC-UA-035` ～ `SPEC-UA-036` | Close Final Verification And Release. |

## LLD Coverage

| LLD Section | Specs |
|---|---|
| 01-domain-model | `SPEC-UA-007`, `SPEC-UA-008`, `SPEC-UA-011`, `SPEC-UA-016` |
| 02-business-invariants | `SPEC-UA-001`, `SPEC-UA-011`, `SPEC-UA-013`, `SPEC-UA-015` |
| 03-state-machine | `SPEC-UA-002`, `SPEC-UA-009`, `SPEC-UA-012`, `SPEC-UA-017` |
| 04-use-cases | `SPEC-UA-005`, `SPEC-UA-009`, `SPEC-UA-012`, `SPEC-UA-015`, `SPEC-UA-017` |
| 05-api-contracts | `SPEC-UA-004`, `SPEC-UA-005`, `SPEC-UA-007`, `SPEC-UA-010`, `SPEC-UA-014`, `SPEC-UA-018`, `SPEC-UA-020`, `SPEC-UA-021`, `SPEC-UA-022`, `SPEC-UA-023`, `SPEC-UA-024`, `SPEC-UA-025`, `SPEC-UA-026`, `SPEC-UA-027` |
| 06-event-contracts | `SPEC-UA-021`, `SPEC-UA-024`, `SPEC-UA-025`, `SPEC-UA-026`, `SPEC-UA-028`, `SPEC-UA-029` |
| 07-data-model | `SPEC-UA-002`, `SPEC-UA-008`, `SPEC-UA-031` |
| 08-transaction-and-outbox | `SPEC-UA-003`, `SPEC-UA-028` |
| 09-concurrency-and-idempotency | `SPEC-UA-003`, `SPEC-UA-033`, `SPEC-UA-034` |
| 10-failure-handling | `SPEC-UA-006`, `SPEC-UA-019`, `SPEC-UA-032`, `SPEC-UA-033` |
| 11-security | `SPEC-UA-004`, `SPEC-UA-006`, `SPEC-UA-010`, `SPEC-UA-013`, `SPEC-UA-014`, `SPEC-UA-016`, `SPEC-UA-018`, `SPEC-UA-019`, `SPEC-UA-023`, `SPEC-UA-027`, `SPEC-UA-031`, `SPEC-UA-032`, `SPEC-UA-034` |
| 12-observability | `SPEC-UA-029`, `SPEC-UA-030` |
| 13-package-and-class-design | `SPEC-UA-001` |
| 14-testing-strategy | `SPEC-UA-020`, `SPEC-UA-022`, `SPEC-UA-035`, `SPEC-UA-036` |

## Final Completion Criteria

- 36 specs have bilingual planning, contracts, persistence rules, acceptance criteria, tests, and traceability.
- Keycloak remains the credential/OIDC/MFA authority; domain 01 stores no credential material.
- Browser and workload identity flows are both contract-tested.
- 01/02/06 authentication, authorization, and step-up E2E paths pass.
- Audit, outbox, recovery, degraded mode, privacy, and release-readiness evidence is complete.
