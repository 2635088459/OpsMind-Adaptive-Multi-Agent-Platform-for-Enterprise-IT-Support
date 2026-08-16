# 04 Memory Knowledge Implementation Roadmap

> Domain: Memory Knowledge
>
> Service: `memory-knowledge-service`
>
> Document Status: Implementation Roadmap

## 1. Overall Goal

Turn the 04 LLD into implementable phases/specs: Working Memory, Long-Term Memory, Knowledge Ingestion, Hybrid Retrieval, Knowledge Graph, and closed contracts with 02 Ticket Workflow and 03 Agent Runtime.

## 2. Phase Overview

| Phase | Name | Specs | Goal |
|---|---|---|---|
| 00 | Engineering Foundation | `SPEC-MK-001` to `SPEC-MK-003` | Establish service package boundaries, schema baseline, and outbox/idempotency/audit baseline. |
| 01 | Working Memory | `SPEC-MK-004` to `SPEC-MK-006` | Implement short-lived ticket/cycle/workflow context storage, merge, query, and archival. |
| 02 | Knowledge Ingestion | `SPEC-MK-007` to `SPEC-MK-009` | Implement formal knowledge document ingestion, parsing/chunking/redaction, embedding, and search activation. |
| 03 | Memory Candidate Pipeline | `SPEC-MK-010` to `SPEC-MK-013` | Extract long-term memory candidates from 02/03 fact events and complete redaction, validation, deduplication, conflict handling, and review. |
| 04 | Versioned Memory Publication | `SPEC-MK-014` to `SPEC-MK-016` | Publish active MemoryVersion and support supersession, deprecation, retention, and deletion. |
| 05 | Retrieval And Knowledge Graph | `SPEC-MK-017` to `SPEC-MK-020` | Implement hybrid retrieval, graph nodes/edges, bounded graph expansion, provenance, and degraded retrieval. |
| 06 | Cross Domain Contracts | `SPEC-MK-021` to `SPEC-MK-024` | Close consumed/published/API contracts with 02 Ticket Workflow and 03 Agent Runtime. |
| 07 | Security And Governance | `SPEC-MK-025` to `SPEC-MK-027` | Implement ACL, classification, PII/secret redaction, prompt-injection defense, and graph traversal guards. |
| 08 | Observability Recovery Admin | `SPEC-MK-028` to `SPEC-MK-030` | Complete metrics/traces/audit, poison/recovery workers, and admin repair/reindex/replay. |
| 09 | Final Verification Release | `SPEC-MK-031` to `SPEC-MK-032` | Complete contract/e2e harness, coverage audit, and release readiness. |

## 3. Closure Principles

- 02 remains the owner of Ticket state; 04 only consumes ticket fact events.
- 03 remains the owner of Workflow state; 04 only provides memory/search/working-memory capability.
- 04 does not execute Tools, approve Policy, or close Tickets.
- Active long-term memory must come from the candidate pipeline and cannot be written directly by Agents.
- Graph is a retrieval and explanation index, not a business state machine.
- Every consumed event is deduplicated through processed-events; every published event goes through outbox.
