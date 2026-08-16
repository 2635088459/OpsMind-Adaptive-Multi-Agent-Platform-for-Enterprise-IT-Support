# SPEC-MK-015 — Publish And Supersede Active Memory

> Domain: Memory Knowledge
>
> Phase: 04 — Versioned Memory Publication
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `08-transaction-and-outbox, 06-event-contracts`
>
> Document Status: Spec Planning

## 1. Goal

Publish approved candidate as active version and supersede old version.

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

- Publication writes memory.published.v1 and optional memory.superseded.v1 via outbox.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
