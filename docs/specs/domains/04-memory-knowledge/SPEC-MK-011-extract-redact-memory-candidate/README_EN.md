# SPEC-MK-011 — Extract And Redact Memory Candidate

> Domain: Memory Knowledge
>
> Phase: 03 — Memory Candidate Pipeline
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `01-domain-model, 04-use-cases, 11-security`
>
> Document Status: Spec Planning

## 1. Goal

Generate MemoryCandidate from ticket/workflow/tool evidence and redacted summaries.

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

- Candidate is not retrievable active memory.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
