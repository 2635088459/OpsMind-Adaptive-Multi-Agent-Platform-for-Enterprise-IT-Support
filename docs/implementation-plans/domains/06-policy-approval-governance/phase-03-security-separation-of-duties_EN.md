# Phase 03 — Security Separation Of Duties

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Phase: 03
>
> Specs: `SPEC-PG-014` to `SPEC-PG-017`
>
> Prerequisite: all 14 LLD slices for `06-policy-approval-governance` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement RBAC/ABAC, separation of duties, approval authenticity, MFA/step-up marker, and override guard.

## 2. Scope

Includes:

- design, code, migration, tests, and traceability for specs in this phase;
- Policy Approval Governance owned aggregates, APIs, events, outbox, rule evaluator, approval workers, or audit capabilities;
- contract closure with 02/03/04/05.

Excludes:

- direct Tool execution;
- Ticket Workflow state machine migration;
- Agent Runtime Workflow state migration;
- Memory content writes;
- cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Main LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-PG-014` | RBAC ABAC Authorization | 11-security, 02-business-invariants |
| 2 | `SPEC-PG-015` | Separation Of Duties Check | 11-security, 02-business-invariants |
| 3 | `SPEC-PG-016` | Approval Authenticity Step Up | 11-security, 05-api-contracts |
| 4 | `SPEC-PG-017` | Sensitive Input And Audit Redaction | 11-security, 12-observability |

## 4. Mandatory Constraints

- 06 may only output governance facts.
- Policy decisions must include policy version and input hash.
- Approval decisions must validate permission, separation of duties, and request linkage.
- Every published event must go through Governance outbox.
- Every consumed event must use processed-event deduplication.

## 5. Exit Criteria

- All spec subdirectories for this phase exist with complete CN/EN docs.
- Every spec has acceptance criteria and test plan.
- No critical rule in mapped LLD sections remains uncovered.
- Contracts with related upstream/downstream domains are testable.
