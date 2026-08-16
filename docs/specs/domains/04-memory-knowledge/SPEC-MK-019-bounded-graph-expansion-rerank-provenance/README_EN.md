# SPEC-MK-019 — Bounded Graph Expansion Rerank Provenance

> Domain: Memory Knowledge
>
> Phase: 05 — Retrieval And Knowledge Graph
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `04-use-cases, 05-api-contracts, 11-security`
>
> Document Status: Spec Planning

## 1. Goal

Expand from seed results with bounded depth and return graphPaths explanations.

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

- Traversal respects ACL/classification and cannot trigger actions.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
