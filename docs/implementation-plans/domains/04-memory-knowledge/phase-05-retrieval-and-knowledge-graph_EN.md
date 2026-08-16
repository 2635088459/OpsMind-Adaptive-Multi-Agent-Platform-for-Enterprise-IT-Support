# Phase 05 — Retrieval And Knowledge Graph

> Domain: Memory Knowledge
>
> Service: `memory-knowledge-service`
>
> Phase: 05
>
> Specs: `SPEC-MK-017` to `SPEC-MK-020`
>
> Prerequisite: the 14 LLD sections for `04-memory-knowledge` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement hybrid retrieval, graph nodes/edges, bounded graph expansion, provenance, and degraded retrieval.

## 2. Scope

Includes:

- design, code, migration, tests, and traceability for the specs in this phase;
- Memory Knowledge-owned aggregates, tables, APIs, events, outbox, or pipelines;
- closed contract checks with 02/03.

Excludes:

- redesigning Ticket Workflow state machine;
- Agent Runtime Workflow state transitions;
- Tool Gateway execution logic;
- Policy auto-approval logic;
- cross-domain distributed transactions.

## 3. Specs

| Order | SPEC | Name | Main LLD Mapping |
|---|---|---|---|
| 1 | `SPEC-MK-017` | Hybrid Retrieval Engine | 04-use-cases, 05-api-contracts, 07-data-model |
| 2 | `SPEC-MK-018` | Knowledge Graph Node Edge Index | 01-domain-model, 07-data-model, 08-transaction-and-outbox |
| 3 | `SPEC-MK-019` | Bounded Graph Expansion Rerank Provenance | 04-use-cases, 05-api-contracts, 11-security |
| 4 | `SPEC-MK-020` | Retrieval Log And Degraded Mode | 10-failure-handling, 12-observability |

## 4. Mandatory Constraints

- Active memory can be created only by the governed candidate/publish pipeline;
- Retrieval results must carry provenance;
- Graph traversal must be bounded and must not bypass ACL/classification;
- Every consumed event must use processed-event deduplication;
- Every published event must go through the Memory outbox;
- 04 must not directly mutate Ticket state or Workflow state.

## 5. Exit Criteria

- All spec subdirectories for this phase exist with complete CN/EN documents;
- Every spec has acceptance criteria and test plan;
- Input/output contracts with 02/03 are testable;
- No critical rule in mapped LLD sections remains uncovered.
