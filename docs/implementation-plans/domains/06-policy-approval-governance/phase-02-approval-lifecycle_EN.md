# Phase 02 — Approval Lifecycle

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Phase: 02
>
> Specs: `SPEC-PG-009` to `SPEC-PG-013`
>
> Prerequisite: all 14 LLD slices for `06-policy-approval-governance` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement ApprovalRequest, grant/deny/cancel/expire, approval decision finality, and event publication.

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
| 1 | `SPEC-PG-009` | Approval Request Aggregate | 01-domain-model, 03-state-machine, 07-data-model |
| 2 | `SPEC-PG-010` | Approval Request API And Event | 05-api-contracts, 06-event-contracts, 08-transaction-and-outbox |
| 3 | `SPEC-PG-011` | Approval Grant Deny API | 05-api-contracts, 03-state-machine, 09-concurrency-and-idempotency |
| 4 | `SPEC-PG-012` | Approval Expiry Cancel | 03-state-machine, 10-failure-handling, 08-transaction-and-outbox |
| 5 | `SPEC-PG-013` | Approval Decision Event Publication | 06-event-contracts, 08-transaction-and-outbox |

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
