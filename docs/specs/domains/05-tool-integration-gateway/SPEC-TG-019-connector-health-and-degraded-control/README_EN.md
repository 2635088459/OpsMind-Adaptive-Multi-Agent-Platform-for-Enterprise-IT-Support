# SPEC-TG-019 — Connector Health And Degraded Control

> Domain: Tool Integration Gateway
>
> Phase: 04 — Retry Reconciliation Cancellation
>
> Service: `tool-integration-gateway`
>
> LLD Mapping: `03-state-machine, 10-failure-handling, 12-observability`
>
> Document Status: Spec Planning

## 1. Goal

Implement ACTIVE/DEGRADED/DISABLED/DEPRECATED states and scheduling restrictions.

## 2. Scope

Includes:

- domain/application/infrastructure/interface design required by this spec;
- corresponding persistence, API/event contract, tests, and acceptance criteria;
- consistency with Tool Gateway LLD boundaries.

Excludes:

- direct Ticket/Workflow state writes; direct Agent tool calls; secret/raw-output leakage; bypassing Policy/Approval; cross-domain distributed transactions.

## 3. Core Rules

- Tool execution must go through Gateway; state must remain separate from Ticket/Workflow; external side effects must be idempotent, auditable, and recoverable; published events must use outbox; consumed events must use processed-event deduplication.
- This spec must not make Gateway own Ticket state or Workflow state.
- Facts produced by this spec must be traceable to ticket, workflow, agent task, connector, and actor.
