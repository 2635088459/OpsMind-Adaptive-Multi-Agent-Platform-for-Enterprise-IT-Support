# Phase 02 — Policy Approval Mediation

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Phase: 02
>
> Specs: `SPEC-TG-007` to `SPEC-TG-009`
>
> Prerequisite: all 14 LLD slices for `05-tool-integration-gateway` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Integrate risk decisions, approval linkage, and approval granted/denied events so high-risk tools cannot bypass approval.

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
| 1 | `SPEC-TG-007` | Policy Risk Decision Integration | 02-business-invariants, 04-use-cases, 06-event-contracts |
| 2 | `SPEC-TG-008` | Approval Required Linkage | 03-state-machine, 06-event-contracts, 08-transaction-and-outbox |
| 3 | `SPEC-TG-009` | Approval Decision Event Consumer | 06-event-contracts, 09-concurrency-and-idempotency, 10-failure-handling |

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
