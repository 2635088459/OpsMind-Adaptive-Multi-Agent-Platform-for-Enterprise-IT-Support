# SPEC-MK-012 — Candidate Validation Dedup Conflict

> Domain: Memory Knowledge
>
> Phase: 03 — Memory Candidate Pipeline
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `02-business-invariants, 03-state-machine`
>
> Document Status: Spec Planning

## 1. Goal

Validate evidence, deduplicate by sourceHash+memoryType, and detect conflicts.

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

- CONFLICTING requires review; DUPLICATE links existing memory.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
