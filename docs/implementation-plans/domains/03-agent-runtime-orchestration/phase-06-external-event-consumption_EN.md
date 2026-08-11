# Phase 06 — External Event Consumption

> Domain: Agent Runtime Orchestration
>
> Service: `agent-runtime-service`
>
> Phase: 06
>
> Specs: `SPEC-ARO-021` to `SPEC-ARO-024`
>
> Prerequisites: the fourteen `03-agent-runtime-orchestration` LLD sections are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Consume approval, verification, ticket-cycle-change events and classify duplicate, stale, and invalid events.

This phase must keep Agent Workflow state separate from Ticket state. Any Ticket lifecycle advancement must go through Ticket Workflow events or command boundaries.

## 2. Scope

Includes:

- design, code, migration, tests, and traceability for specs in this phase;
- Runtime-owned aggregates, states, checkpoints, outbox, or event handling capability;
- consistency checks against the related LLD sections.

Excludes:

- redesigning the Ticket Workflow main state machine;
- direct Tool calls from Agents;
- cross-domain distributed transactions;
- external side effects that bypass Tool Gateway.

## 3. Specs

| Order | SPEC | Name | Main LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-ARO-021` | Consume Approval Events | 04-use-cases, 06-event-contracts |
| 2 | `SPEC-ARO-022` | Consume Verification Events | 04-use-cases, 06-event-contracts |
| 3 | `SPEC-ARO-023` | Consume Ticket Cycle Events | 06-event-contracts, 10-failure-handling |
| 4 | `SPEC-ARO-024` | Duplicate Stale Invalid Event Classification | 09-concurrency-and-idempotency, 10-failure-handling |

## 4. Mandatory Constraints

- Agents must not call Tools directly; Tool Gateway is mandatory;
- every external side effect must be preceded by a recoverable checkpoint;
- every consumed event must use processed-event de-duplication;
- every published event must go through Runtime outbox;
- Pause / Resume commands must be idempotent;
- Runtime must recover from checkpoints, leases, cursors, and outbox after crash.

## 5. Exit Criteria

- all spec subdirectories for this phase exist with complete CN/EN docs;
- every spec has acceptance criteria and a test plan;
- no key rule from the mapped LLD sections is uncovered;
- implementation can be delivered, tested, and rolled back at single-spec granularity.
