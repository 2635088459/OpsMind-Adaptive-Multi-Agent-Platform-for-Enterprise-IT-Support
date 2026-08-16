# SPEC-MK-020 — Retrieval Log And Degraded Mode

> Domain: Memory Knowledge
>
> Phase: 05 — Retrieval And Knowledge Graph
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `10-failure-handling, 12-observability`
>
> Document Status: Spec Planning

## 1. Goal

Record retrieval logs, graph paths, degraded reasons, and latency.

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

- If graph/vector down, return degraded response without fabricated evidence.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
