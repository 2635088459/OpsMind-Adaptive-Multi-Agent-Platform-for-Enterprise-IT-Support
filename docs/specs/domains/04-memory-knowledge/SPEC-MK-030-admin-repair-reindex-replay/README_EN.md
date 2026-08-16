# SPEC-MK-030 — Admin Repair Reindex Replay

> Domain: Memory Knowledge
>
> Phase: 08 — Observability Recovery Admin
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `05-api-contracts, 10-failure-handling`
>
> Document Status: Spec Planning

## 1. Goal

Implement admin repair APIs for reindex, graph rebuild, outbox replay, retention retry.

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

- All admin actions audited and authorized.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
