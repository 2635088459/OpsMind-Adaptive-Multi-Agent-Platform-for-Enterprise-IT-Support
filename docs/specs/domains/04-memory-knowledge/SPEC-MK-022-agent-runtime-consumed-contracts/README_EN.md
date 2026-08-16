# SPEC-MK-022 — Agent Runtime Consumed Contracts

> Domain: Memory Knowledge
>
> Phase: 06 — Cross Domain Contracts
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `06-event-contracts`
>
> Document Status: Spec Planning

## 1. Goal

Lock 03->04 workflow.completed/failed and tool/verification evidence references.

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

- 03 remains workflow state owner; 04 only consumes facts/evidence refs.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
