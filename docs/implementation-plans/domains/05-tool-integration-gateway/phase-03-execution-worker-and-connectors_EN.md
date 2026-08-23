# Phase 03 — Execution Worker And Connectors

> Domain: Tool Integration Gateway
>
> Service: `tool-integration-gateway`
>
> Phase: 03
>
> Specs: `SPEC-TG-010` to `SPEC-TG-015`
>
> Prerequisite: all 14 LLD slices for `05-tool-integration-gateway` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement scheduling, worker claim/lease, connector SDK, credential preparation, operation keys, side-effect guard, result normalization, and tool.completed publication.

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
| 1 | `SPEC-TG-010` | Execution Scheduling Worker Lease | 03-state-machine, 08-transaction-and-outbox, 09-concurrency-and-idempotency |
| 2 | `SPEC-TG-011` | Connector SDK And Built-In Fake Connector | 13-package-and-class-design, 14-testing-strategy |
| 3 | `SPEC-TG-012` | Credential Binding Invocation Preparation | 01-domain-model, 11-security |
| 4 | `SPEC-TG-013` | Operation Key Side Effect Guard | 09-concurrency-and-idempotency, 10-failure-handling |
| 5 | `SPEC-TG-014` | Result Envelope Normalization Redaction | 01-domain-model, 11-security, 05-api-contracts |
| 6 | `SPEC-TG-015` | Tool Completed Event Publication | 06-event-contracts, 08-transaction-and-outbox |

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
