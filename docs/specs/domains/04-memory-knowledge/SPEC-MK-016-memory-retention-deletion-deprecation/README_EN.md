# SPEC-MK-016 — Memory Retention Deletion Deprecation

> Domain: Memory Knowledge
>
> Phase: 04 — Versioned Memory Publication
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `03-state-machine, 11-security`
>
> Document Status: Spec Planning

## 1. Goal

Implement deprecate/delete/retention visibility and tombstones.

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

- Deleted memory not retrievable; audit metadata remains.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
