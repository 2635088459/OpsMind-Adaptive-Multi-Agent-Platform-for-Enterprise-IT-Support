# Phase 01 — Request Intake And Registry

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Phase: 01
>
> Specs: `SPEC-TG-004` to `SPEC-TG-006`
>
> Prerequisite: all 14 LLD slices for `05-tool-integration-gateway` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement ToolRequest aggregate, Runtime API, and Connector/Capability registry as the only tool execution entry point.

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
| 1 | `SPEC-TG-004` | Tool Request Aggregate State Machine | 01-domain-model, 03-state-machine, 02-business-invariants |
| 2 | `SPEC-TG-005` | Runtime Tool Request API | 05-api-contracts, 09-concurrency-and-idempotency |
| 3 | `SPEC-TG-006` | Connector Capability Registry | 01-domain-model, 05-api-contracts, 07-data-model |

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
