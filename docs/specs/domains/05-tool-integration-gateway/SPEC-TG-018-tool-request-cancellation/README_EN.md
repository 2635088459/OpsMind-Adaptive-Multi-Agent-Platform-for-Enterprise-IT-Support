# SPEC-TG-018 — Tool Request Cancellation

> Domain: Tool Integration Gateway
>
> Phase: 04 — Retry Reconciliation Cancellation
>
> Service: `tool-integration-gateway`
>
> LLD Mapping: `04-use-cases, 09-concurrency-and-idempotency, 05-api-contracts`
>
> Document Status: Spec Planning

## 1. Goal

Implement pending/running cancellation, connector cancel hook, and race handling with completion.

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
