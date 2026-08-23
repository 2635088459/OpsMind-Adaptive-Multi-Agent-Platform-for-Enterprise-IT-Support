# SPEC-TG-009 — Approval Decision Event Consumer

> Domain: Tool Integration Gateway
>
> Phase: 02 — Policy Approval Mediation
>
> Service: `tool-integration-gateway`
>
> LLD Mapping: `06-event-contracts, 09-concurrency-and-idempotency, 10-failure-handling`
>
> Document Status: Spec Planning

## 1. Goal

Consume approval.granted/denied, validate linkage, and resume or finalize Tool Request.

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
