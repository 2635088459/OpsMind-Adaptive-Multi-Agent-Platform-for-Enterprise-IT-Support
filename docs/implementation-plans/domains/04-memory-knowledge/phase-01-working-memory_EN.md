# Phase 01 — Working Memory

> Domain: Memory Knowledge
>
> Service: `memory-knowledge-service`
>
> Phase: 01
>
> Specs: `SPEC-MK-004` to `SPEC-MK-006`
>
> Prerequisite: the 14 LLD sections for `04-memory-knowledge` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Implement short-lived ticket/cycle/workflow context storage, merge, query, and archival.

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
| 1 | `SPEC-MK-004` | Working Memory Aggregate | 01-domain-model, 02-business-invariants |
| 2 | `SPEC-MK-005` | Working Memory Update Merge API | 04-use-cases, 05-api-contracts, 09-concurrency-and-idempotency |
| 3 | `SPEC-MK-006` | Working Memory Query Archive | 05-api-contracts, 03-state-machine |

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
