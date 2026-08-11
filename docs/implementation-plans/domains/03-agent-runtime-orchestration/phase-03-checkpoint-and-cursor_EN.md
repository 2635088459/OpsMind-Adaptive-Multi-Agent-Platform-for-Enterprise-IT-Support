# Phase 03 — Checkpoint and Cursor

> Domain: Agent Runtime Orchestration
>
> Service: `agent-runtime-service`
>
> Phase: 03
>
> Specs: `SPEC-ARO-011` to `SPEC-ARO-013`
>
> Prerequisites: the fourteen `03-agent-runtime-orchestration` LLD sections are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement recoverable checkpoints, waiting-state snapshots, and event cursor/processed-event de-duplication.

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
| 1 | `SPEC-ARO-011` | Checkpoint Store | 01-domain-model, 07-data-model |
| 2 | `SPEC-ARO-012` | Checkpoint Around External Waits | 03-state-machine, 08-transaction-and-outbox |
| 3 | `SPEC-ARO-013` | Event Cursor and Processed Events | 01-domain-model, 09-concurrency-and-idempotency |

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
