# Phase 00 — Engineering Foundation

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Phase: 00
>
> Specs: `SPEC-PG-001` to `SPEC-PG-003`
>
> Prerequisite: all 14 LLD slices for `06-policy-approval-governance` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Establish service boundaries, schema baseline, outbox/processed-event/audit baseline for policy-approval-governance-service.

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
| 1 | `SPEC-PG-001` | Policy Governance Module And Package Boundaries | 13-package-and-class-design, 02-business-invariants |
| 2 | `SPEC-PG-002` | Policy Governance Schema Baseline | 07-data-model, 03-state-machine |
| 3 | `SPEC-PG-003` | Governance Outbox Idempotency Audit Baseline | 08-transaction-and-outbox, 09-concurrency-and-idempotency, 12-observability |

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
