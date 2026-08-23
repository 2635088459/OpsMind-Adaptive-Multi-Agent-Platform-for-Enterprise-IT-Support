# Phase 07 — Observability Audit Compliance

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Phase: 07
>
> Specs: `SPEC-PG-029` to `SPEC-PG-031`
>
> Prerequisite: all 14 LLD slices for `06-policy-approval-governance` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Add metrics/logs/traces, governance audit query, audit integrity, and compliance reporting.

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
| 1 | `SPEC-PG-029` | Governance Metrics Logs Traces | 12-observability |
| 2 | `SPEC-PG-030` | Governance Audit Query API | 12-observability, 05-api-contracts, 07-data-model |
| 3 | `SPEC-PG-031` | Audit Integrity Compliance Reporting | 11-security, 12-observability |

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
