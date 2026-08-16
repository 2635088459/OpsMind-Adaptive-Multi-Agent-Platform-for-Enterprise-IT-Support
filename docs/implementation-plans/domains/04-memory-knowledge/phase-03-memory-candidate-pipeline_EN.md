# Phase 03 — Memory Candidate Pipeline

> Domain: Memory Knowledge
>
> Service: `memory-knowledge-service`
>
> Phase: 03
>
> Specs: `SPEC-MK-010` to `SPEC-MK-013`
>
> Prerequisite: the 14 LLD sections for `04-memory-knowledge` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Extract long-term memory candidates from 02/03 fact events and complete redaction, validation, deduplication, conflict handling, and review.

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
| 1 | `SPEC-MK-010` | Consume Ticket Memory Source Events | 06-event-contracts, 09-concurrency-and-idempotency |
| 2 | `SPEC-MK-011` | Extract And Redact Memory Candidate | 01-domain-model, 04-use-cases, 11-security |
| 3 | `SPEC-MK-012` | Candidate Validation Dedup Conflict | 02-business-invariants, 03-state-machine |
| 4 | `SPEC-MK-013` | Memory Candidate Review API | 05-api-contracts, 11-security, 12-observability |

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
