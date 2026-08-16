# SPEC-MK-005 — Working Memory Update Merge API

> Domain: Memory Knowledge
>
> Phase: 01 — Working Memory
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `04-use-cases, 05-api-contracts, 09-concurrency-and-idempotency`
>
> Document Status: Spec Planning

## 1. Goal

Implement patch/merge API with expectedVersion optimistic locking.

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

- No last-write-wins; rejected hypotheses retain reason; sensitive fields rejected or redacted.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
