# 04 Memory Knowledge Phase / Spec Coverage Matrix

## Goal

This matrix verifies that the `04-memory-knowledge` phase/spec breakdown covers the 14 LLD sections and can close the collaboration loop with `02-ticket-workflow` and `03-agent-runtime-orchestration`.

## Phase / Spec Overview

| Phase | Specs | Closure Goal |
|---|---|---|
| 00 Engineering Foundation | `SPEC-MK-001` to `SPEC-MK-003` | service boundary, schema, outbox, processed-events, audit baseline |
| 01 Working Memory | `SPEC-MK-004` to `SPEC-MK-006` | 03 Runtime can save/read ticket-scoped short-term context |
| 02 Knowledge Ingestion | `SPEC-MK-007` to `SPEC-MK-009` | formal documents can be ingested, redacted, chunked, embedded, and activated |
| 03 Memory Candidate Pipeline | `SPEC-MK-010` to `SPEC-MK-013` | 02/03 fact events can produce governed memory candidates |
| 04 Versioned Memory Publication | `SPEC-MK-014` to `SPEC-MK-016` | candidates can publish active memory with supersede/delete |
| 05 Retrieval And Knowledge Graph | `SPEC-MK-017` to `SPEC-MK-020` | Runtime can receive hybrid retrieval + graph path + provenance |
| 06 Cross Domain Contracts | `SPEC-MK-021` to `SPEC-MK-024` | lock 02->04, 03->04, 04->downstream, and 03 client contracts |
| 07 Security And Governance | `SPEC-MK-025` to `SPEC-MK-027` | ACL, PII/secret, prompt injection, graph traversal guard |
| 08 Observability Recovery Admin | `SPEC-MK-028` to `SPEC-MK-030` | metrics/traces/audit, poison/recovery, admin repair |
| 09 Final Verification Release | `SPEC-MK-031` to `SPEC-MK-032` | contract/e2e harness, final coverage audit, release readiness |

## LLD Coverage

| LLD Section | Covered Specs |
|---|---|
| 01-domain-model | `SPEC-MK-004`, `007`, `011`, `014`, `018` |
| 02-business-invariants | `SPEC-MK-001`, `004`, `012`, `018`, `025` |
| 03-state-machine | `SPEC-MK-006`, `012`, `014`, `016`, `018` |
| 04-use-cases | `SPEC-MK-005`, `008`, `011`, `017`, `019` |
| 05-api-contracts | `SPEC-MK-005`, `006`, `007`, `013`, `017`, `019`, `024`, `030` |
| 06-event-contracts | `SPEC-MK-010`, `021`, `022`, `023` |
| 07-data-model | `SPEC-MK-002`, `009`, `014`, `017`, `018`, `020` |
| 08-transaction-and-outbox | `SPEC-MK-003`, `009`, `015`, `018`, `023` |
| 09-concurrency-and-idempotency | `SPEC-MK-003`, `005`, `010`, `012`, `018`, `020` |
| 10-failure-handling | `SPEC-MK-009`, `020`, `029`, `030` |
| 11-security | `SPEC-MK-008`, `013`, `025`, `026`, `027` |
| 12-observability | `SPEC-MK-003`, `020`, `028`, `030` |
| 13-package-and-class-design | `SPEC-MK-001`, `018`, `024` |
| 14-testing-strategy | `SPEC-MK-031`, `032` |

## Closure With 02 Ticket Workflow

- `SPEC-MK-010`: consumes `ticket.resolved.v1` / `ticket.closed.v1` and starts candidate extraction.
- `SPEC-MK-021`: locks 02 outbox envelope compatibility, duplicate delivery, and invalid-payload behavior.
- `SPEC-MK-011` to `013`: produce candidates from ticket facts but never mutate Ticket state directly.
- `SPEC-MK-023`: publishes memory events for evaluation / analytics without requiring 02 to directly change Ticket state.

## Closure With 03 Agent Runtime

- `SPEC-MK-004` to `006`: 03 can write/read Working Memory but cannot write active long-term memory.
- `SPEC-MK-017` to `020`: 03 can call search and receive evidence, graph paths, and provenance.
- `SPEC-MK-022`: consumes `workflow.completed.v1` / `workflow.failed.v1` as automation trace/evidence.
- `SPEC-MK-024`: locks the 03 MemoryClient API, including degraded mode, ACL, and graph path shape.

## Graph Closure

- `SPEC-MK-018`: graph_nodes / graph_edges model, tables, and upsert.
- `SPEC-MK-019`: bounded graph expansion, rerank, and provenance response.
- `SPEC-MK-020`: graph degraded mode and retrieval log.
- `SPEC-MK-027`: graph traversal guard, preventing ACL/classification bypass and treating graph paths as evidence, not execution plans.

## Final Completion Standard

By the end of `SPEC-MK-032`, the project must prove:

- all 14 LLD sections for 04 are covered by specs;
- 02->04 ticket event contracts run;
- 03->04 workflow/search/client contracts run;
- active memory can be published only by the candidate pipeline;
- retrieval results always include provenance;
- graph paths are explainable and bounded;
- deletion, retention, redaction, audit, and recovery have test plans and implementation entry points.
