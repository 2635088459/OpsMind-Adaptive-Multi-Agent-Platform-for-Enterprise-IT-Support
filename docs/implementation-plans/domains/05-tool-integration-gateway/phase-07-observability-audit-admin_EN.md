# Phase 07 — Observability Audit Admin

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Phase: 07
>
> Specs: `SPEC-TG-026` to `SPEC-TG-029`
>
> Prerequisite: all 14 LLD slices for `05-tool-integration-gateway` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Add metrics/logs/traces, audit query, outbox poison/admin repair, and connector admin lifecycle.

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
| 1 | `SPEC-TG-026` | Metrics Logs Traces | 12-observability |
| 2 | `SPEC-TG-027` | Audit Query And Admin Reporting | 12-observability, 11-security |
| 3 | `SPEC-TG-028` | Outbox Poison Replay Admin Repair | 08-transaction-and-outbox, 10-failure-handling |
| 4 | `SPEC-TG-029` | Connector Admin Lifecycle API | 05-api-contracts, 03-state-machine |

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
