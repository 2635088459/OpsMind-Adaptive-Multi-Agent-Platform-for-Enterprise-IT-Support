# SPEC-MK-018 — Knowledge Graph Node Edge Index

> Domain: Memory Knowledge
>
> Phase: 05 — Retrieval And Knowledge Graph
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `01-domain-model, 07-data-model, 08-transaction-and-outbox`
>
> Document Status: Spec Planning

## 1. Goal

Implement graph_nodes and graph_edges with evidence-backed upsert.

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

- Edges require evidenceRefs; stableKey prevents duplicate entities.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
