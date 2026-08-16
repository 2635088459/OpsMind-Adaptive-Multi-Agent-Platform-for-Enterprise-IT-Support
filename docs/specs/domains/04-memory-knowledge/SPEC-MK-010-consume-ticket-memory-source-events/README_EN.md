# SPEC-MK-010 — Consume Ticket Memory Source Events

> Domain: Memory Knowledge
>
> Phase: 03 — Memory Candidate Pipeline
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `06-event-contracts, 09-concurrency-and-idempotency`
>
> Document Status: Spec Planning

## 1. Goal

Consume ticket.resolved.v1 and ticket.closed.v1 from 02 for candidate extraction.

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

- 02 remains system of record; 04 only extracts from fact events.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
