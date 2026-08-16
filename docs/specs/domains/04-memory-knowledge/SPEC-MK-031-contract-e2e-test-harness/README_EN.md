# SPEC-MK-031 — Contract And E2E Test Harness

> Domain: Memory Knowledge
>
> Phase: 09 — Final Verification Release
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `14-testing-strategy`
>
> Document Status: Spec Planning

## 1. Goal

Build unit/application/integration/contract/e2e harness across 02/03/04 contracts.

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

- Must prove 02->04 and 03->04 compatibility and 03 memory client behavior.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
