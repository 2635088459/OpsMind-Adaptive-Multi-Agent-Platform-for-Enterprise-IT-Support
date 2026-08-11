# Agent Runtime Orchestration — Implementation Roadmap

> Domain: Agent Runtime Orchestration
>
> Service: `agent-runtime-service`
>
> LLD: `docs/low-level-design/domains/03-agent-runtime-orchestration`
>
> Spec Prefix: `SPEC-ARO`
>
> Document Status: Implementation Plan

## 1. Goal

This roadmap turns the fourteen LLD sections of 03 Agent Runtime Orchestration into implementable phases and specs.

Runtime orchestrates agent automation execution. It does not own Ticket lifecycle state. Ticket state is owned by Ticket Workflow. Runtime owns only Agent Workflow state and collaborates through events, read-only queries, and controlled command boundaries.

## 2. Phase Overview

| Phase | Name | Specs | Goal |
|---|---|---|---|
| Phase 00 | Engineering Foundation | `SPEC-ARO-001` to `SPEC-ARO-003` | Establish engineering boundaries, schema baseline, outbox, and idempotency foundations for Agent Runtime Orchestration. |
| Phase 01 | Workflow Instance Lifecycle | `SPEC-ARO-004` to `SPEC-ARO-006` | Define the Workflow Instance aggregate and start automation instances from `ticket.created`. |
| Phase 02 | Agent Task Orchestration | `SPEC-ARO-007` to `SPEC-ARO-010` | Build Agent Task, planner task graph, claim lease, completion, and join policy. |
| Phase 03 | Checkpoint and Cursor | `SPEC-ARO-011` to `SPEC-ARO-013` | Implement recoverable checkpoints, waiting-state snapshots, and event cursor/processed-event de-duplication. |
| Phase 04 | Pause Resume Control | `SPEC-ARO-014` to `SPEC-ARO-016` | Implement idempotent pause/resume commands, pause generation, and stale worker-result protection. |
| Phase 05 | Tool Gateway Mediation | `SPEC-ARO-017` to `SPEC-ARO-020` | Ensure Agents cannot call Tools directly; every tool side effect must be a persisted Runtime Tool Request routed through Tool Gateway. |
| Phase 06 | External Event Consumption | `SPEC-ARO-021` to `SPEC-ARO-024` | Consume approval, verification, ticket-cycle-change events and classify duplicate, stale, and invalid events. |
| Phase 07 | Runtime Event Publishing | `SPEC-ARO-025` to `SPEC-ARO-027` | Publish workflow and agent.task events through Runtime outbox without synchronous broker publishing in transactions. |
| Phase 08 | Failure Recovery and Reconciliation | `SPEC-ARO-028` to `SPEC-ARO-031` | Implement runtime crash recovery, expired lease recovery, outbox replay, and admin reconciliation repair. |
| Phase 09 | Security Observability and Release Readiness | `SPEC-ARO-032` to `SPEC-ARO-036` | Complete authorization, redaction, metrics/tracing, contract tests, and final phase/spec coverage audit. |

## 3. Key Design Answers

- Workflow Instance: one automation orchestration instance around a ticket/cycle.
- Agent Task: the smallest schedulable work unit assigned to an Agent role inside a Workflow.
- Checkpoint: persisted recovery point with structured JSON payload, version, cursor, and checksum.
- Pause / Resume: idempotent through idempotency key, workflow version, pause generation, and outbox de-duplication.
- Multi-agent orchestration: planner, task graph, claim lease, join policy, and coordinator.
- Crash recovery: checkpoint, pending task, event cursor, outbox replay, and lease expiry.
- Event consumption: `ticket.created`, `approval.granted`, `tool.completed`, and `verification.completed` must be de-duplicated and correlation-validated.
- Event publishing: `workflow.started`, `workflow.paused`, and `agent.task.completed` must go through Runtime outbox.
- Tool invocation: Agents cannot call Tools directly; Tool Gateway is mandatory.
- State separation: Agent Workflow state and Ticket state are separate and do not share state machines or transaction boundaries.

## 4. Implementation Order

Proceed from Phase 00 to Phase 09. Each spec should complete docs, acceptance criteria, migration notes, and test plan before code implementation.

## 5. Audit Point

After each phase, create a traceability audit verifying coverage of the fourteen LLD sections, key events, idempotency policy, Tool Gateway boundary, and crash recovery paths.
