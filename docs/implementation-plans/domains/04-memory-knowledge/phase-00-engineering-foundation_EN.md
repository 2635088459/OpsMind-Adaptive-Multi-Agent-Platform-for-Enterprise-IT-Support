# Phase 00 — Engineering Foundation

> Domain: Memory Knowledge
>
> Service: `memory-knowledge-service`
>
> Phase: 00
>
> Specs: `SPEC-MK-001` to `SPEC-MK-003`
>
> Prerequisite: the 14 LLD sections for `04-memory-knowledge` are frozen
>
> Document Status: Implementation Plan

## 1. Phase Goal

Establish service package boundaries, schema baseline, and outbox/idempotency/audit baseline.

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
| 1 | `SPEC-MK-001` | Memory Module And Package Boundaries | 13-package-and-class-design, 02-business-invariants |
| 2 | `SPEC-MK-002` | Memory Schema Baseline | 07-data-model |
| 3 | `SPEC-MK-003` | Outbox Idempotency Audit Baseline | 08-transaction-and-outbox, 09-concurrency-and-idempotency, 12-observability |

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
