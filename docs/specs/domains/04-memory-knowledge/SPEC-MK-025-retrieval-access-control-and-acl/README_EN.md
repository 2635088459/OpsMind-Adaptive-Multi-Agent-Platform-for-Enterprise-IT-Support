# SPEC-MK-025 — Retrieval Access Control And ACL

> Domain: Memory Knowledge
>
> Phase: 07 — Security And Governance
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `11-security, 02-business-invariants`
>
> Document Status: Spec Planning

## 1. Goal

Implement access scope, document ACL, memory classification, role filters.

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

- Search cannot cross tenant/role/classification boundaries.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
