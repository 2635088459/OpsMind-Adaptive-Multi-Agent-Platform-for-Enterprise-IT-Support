# SPEC-MK-014 — Memory And Version Aggregate

> Domain: Memory Knowledge
>
> Phase: 04 — Versioned Memory Publication
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `01-domain-model, 03-state-machine`
>
> Document Status: Spec Planning

## 1. Goal

Implement Memory logical identity and immutable MemoryVersion.

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

- One active version per memoryId.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
