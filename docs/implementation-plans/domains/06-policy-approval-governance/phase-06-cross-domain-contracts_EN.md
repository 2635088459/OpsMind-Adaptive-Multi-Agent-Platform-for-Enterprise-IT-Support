# Phase 06 — Cross Domain Contracts

> Domain: Policy Approval Governance
>
> Service: `policy-approval-governance-service`
>
> Phase: 06
>
> Specs: `SPEC-PG-025` to `SPEC-PG-028`
>
> Prerequisite: all 14 LLD slices for `06-policy-approval-governance` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Close governance contracts with 05 Tool Gateway, 03 Agent Runtime, 02 Ticket Workflow, and 04 Memory Knowledge.

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
| 1 | `SPEC-PG-025` | 05 Tool Gateway Policy Approval Contract | 06-event-contracts, 05-api-contracts, 14-testing-strategy |
| 2 | `SPEC-PG-026` | 03 Agent Runtime Governance Contract | 06-event-contracts, 05-api-contracts |
| 3 | `SPEC-PG-027` | 02 Ticket Workflow Governance Contract | 06-event-contracts, 04-use-cases |
| 4 | `SPEC-PG-028` | 04 Memory Knowledge Policy Contract | 06-event-contracts, 05-api-contracts, 11-security |

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
