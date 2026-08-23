# Phase 04 — Retry Reconciliation Cancellation

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Phase: 04
>
> Specs: `SPEC-TG-016` to `SPEC-TG-019`
>
> Prerequisite: all 14 LLD slices for `05-tool-integration-gateway` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement retry policy, timeout/partial-side-effect reconciliation, cancellation, and connector health states.

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
| 1 | `SPEC-TG-016` | Retry Policy And Retry Scheduling | 10-failure-handling, 09-concurrency-and-idempotency |
| 2 | `SPEC-TG-017` | Timeout Partial Side Effect Reconciliation | 10-failure-handling, 03-state-machine |
| 3 | `SPEC-TG-018` | Tool Request Cancellation | 04-use-cases, 09-concurrency-and-idempotency, 05-api-contracts |
| 4 | `SPEC-TG-019` | Connector Health And Degraded Control | 03-state-machine, 10-failure-handling, 12-observability |

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
