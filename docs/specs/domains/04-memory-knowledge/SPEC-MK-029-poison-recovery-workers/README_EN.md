# SPEC-MK-029 — Poison And Recovery Workers

> Domain: Memory Knowledge
>
> Phase: 08 — Observability Recovery Admin
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `10-failure-handling`
>
> Document Status: Spec Planning

## 1. Goal

Implement poison event/document handling, embedding/index/graph/outbox recovery workers.

## 2. Scope

Includes:

- domain/application/infrastructure/interface design required by this spec;
- persistence, API/event contract, tests, and acceptance criteria;
- boundary consistency with 02 Ticket Workflow and 03 Agent Runtime.

Excludes:

- mutating 02 Ticket state;
- mutating 03 Workflow state;
- direct Tool execution;
- unvalidated active-memory writes;
- cross-domain distributed transactions.

## 3. Core Rules

- Recovery never fabricates evidence or active memory.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
