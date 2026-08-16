# SPEC-MK-001 — Memory Module And Package Boundaries

> Domain: Memory Knowledge
>
> Phase: 00 — Engineering Foundation
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `13-package-and-class-design, 02-business-invariants`
>
> Document Status: Spec Planning

## 1. Goal

Create Python service skeleton, domain/application/infrastructure/interfaces boundaries, settings and container.

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

- No Ticket/Workflow/Tool state ownership; no direct tool execution; no cross-domain transactions.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
