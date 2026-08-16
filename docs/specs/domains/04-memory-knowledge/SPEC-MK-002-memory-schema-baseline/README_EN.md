# SPEC-MK-002 — Memory Schema Baseline

> Domain: Memory Knowledge
>
> Phase: 00 — Engineering Foundation
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `07-data-model`
>
> Document Status: Spec Planning

## 1. Goal

Create PostgreSQL schema baseline for working memory, candidates, memories, documents, chunks, embeddings, graph tables, retrieval logs.

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

- Schema must include versioning, visibility, unique keys, and pgvector-ready embedding storage.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
