# Phase 06 — Cross Domain Contracts

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Phase: 06
>
> Specs: `SPEC-TG-022` to `SPEC-TG-025`
>
> Prerequisite: all 14 LLD slices for `05-tool-integration-gateway` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Close contracts with 03 Runtime, 06 Policy/Approval, 04 Memory Knowledge, and 02 Ticket/Workflow.

## 2. Scope

Includes:

- design, code, migration, tests, and traceability for specs in this phase;
- Tool Gateway owned aggregates, APIs, events, outbox, connectors, or workers;
- contract closure with 02/03/04/06.

Excludes:

- redesigning Ticket Workflow state machine;
- migrating Agent Runtime Workflow state;
- active long-term Memory writes;
- moving Policy rule ownership;
- cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Main LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-TG-022` | 03 Agent Runtime Tool Contract | 05-api-contracts, 06-event-contracts, 14-testing-strategy |
| 2 | `SPEC-TG-023` | 06 Policy Approval Contract | 06-event-contracts, 02-business-invariants |
| 3 | `SPEC-TG-024` | 04 Memory Evidence Contract | 06-event-contracts, 11-security |
| 4 | `SPEC-TG-025` | 02 Ticket Workflow Traceability Contract | 06-event-contracts, 02-business-invariants |

## 4. Mandatory Constraints

- Agents must not call Tools directly.
- Tool execution must not directly advance Ticket/Workflow state.
- Every external side effect must be idempotent, auditable, and recoverable.
- High-risk capabilities must go through 06 approval.
- Secrets/raw output must not leak into events, logs, or memory.

## 5. Exit Criteria

- All spec subdirectories for this phase exist with complete CN/EN docs.
- Every spec has acceptance criteria and test plan.
- No critical rule in mapped LLD sections remains uncovered.
- Contracts with related upstream/downstream domains are testable.
