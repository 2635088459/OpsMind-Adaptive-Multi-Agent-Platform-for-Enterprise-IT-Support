# Phase 01 — Policy Model And Decision Engine

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Phase: 01
>
> Specs: `SPEC-PG-004` to `SPEC-PG-008`
>
> Prerequisite: all 14 LLD slices for `06-policy-approval-governance` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement Policy/Rule/Version model, decision API, rule evaluator, risk mapping, and constraints output.

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
| 1 | `SPEC-PG-004` | Policy Rule Domain Model | 01-domain-model, 02-business-invariants |
| 2 | `SPEC-PG-005` | Policy Decision Aggregate | 01-domain-model, 03-state-machine, 07-data-model |
| 3 | `SPEC-PG-006` | Decision Evaluate API | 05-api-contracts, 09-concurrency-and-idempotency |
| 4 | `SPEC-PG-007` | Rule Evaluator And Risk Mapping | 04-use-cases, 10-failure-handling |
| 5 | `SPEC-PG-008` | Decision Constraints And Policy Version Snapshot | 02-business-invariants, 09-concurrency-and-idempotency |

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
