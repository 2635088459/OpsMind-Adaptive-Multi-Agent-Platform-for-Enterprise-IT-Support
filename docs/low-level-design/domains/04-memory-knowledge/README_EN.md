# Memory Knowledge LLD

## Scope

This directory defines the low-level design for `04-memory-knowledge`. The domain provides searchable, traceable, versioned, and deletable memory and knowledge capabilities for Agent Runtime.

Memory Knowledge does not own ticket state, execute tools, make policy decisions, or directly transition workflow state. It answers two questions:

- What confirmed facts, hypotheses, evidence, and context exist inside the current ticket / workflow?
- Which validated prior tickets, runbooks, organizational facts, and agent experience can be returned as citeable evidence for Runtime / Agents?

## Core Answers

- Working Memory is short-lived context scoped to a ticket/cycle/workflow. It does not automatically become long-term memory.
- Long-Term Memory is a governed asset created only after extraction, redaction, evidence validation, deduplication, conflict detection, and versioning.
- A Knowledge Document is a formal source such as a runbook, FAQ, SOP, service catalog entry, or incident advisory.
- Memory Graph / Knowledge Graph is an explainable relationship layer connecting tickets, memories, document chunks, services, symptoms, root causes, actions, owners, and tool evidence. It is not a new business state machine; it is a structured index for retrieval, conflict detection, recommendation, and provenance.
- Every Retrieval Result must carry provenance: source type, source id, chunk id, memory version, score, and redaction status.
- A Memory Candidate is not Memory. Candidates must pass validation, deduplication, conflict detection, and usefulness scoring before becoming active memory.
- Agent Runtime may query Memory / Knowledge, but it cannot directly write active long-term memory. Writes must flow through events and the governed pipeline.
- Agents must not treat retrieval as final truth. Retrieval is evidence input; ticket decisions remain constrained by Ticket Workflow, Policy, Tools, and Verification.
- PII, secrets, and raw tool output must not enter long-term memory. Store summaries, hashes, references, and redacted evidence where needed.
- Deletion, retention, and supersession must be auditable and must cover memory rows, versions, embeddings, chunks, and retrieval visibility.
- 04 connects to 03 through `memory.search` APIs and events; it connects to 02 through `ticket.resolved` / `ticket.closed` and related events for candidate extraction.

## Why Graph Structure Is Needed

Vector retrieval can answer "what is semantically similar", but IT support also needs "why is this related" and "what should be inspected next". Graph structure represents stable relationships:

- which application / service a symptom affects;
- which evidence supported a root cause;
- which runbook step resolved a failure mode;
- which historical tickets share service, owner, action, or verification outcome with the current ticket;
- which memory conflicts with or supports a document chunk.

MVP does not introduce a standalone graph database. Graph is stored in PostgreSQL tables, `graph_nodes` and `graph_edges`, and combined with pgvector / full-text retrieval. This follows the shared technology baseline and keeps transactions, audit, deletion, and tests straightforward.

## How Graph Is Used

Search pipeline order:

1. normalize query and calculate access scope;
2. use vector / keyword retrieval to find seed memories and document chunks;
3. run bounded graph expansion from seed nodes;
4. rerank by edge type, confidence, recency, and source trust;
5. return snippet + graph path + provenance;
6. write retrieval log with graph paths that influenced context.

Graph expansion must enforce depth, node-count, classification, and ACL limits. It must not traverse without bounds or cross access-control boundaries.

## 14 LLD Sections

1. `01-domain-model`: Working Memory, Memory, Memory Candidate, Knowledge Document, Chunk, Embedding, Graph Node/Edge, Retrieval Log.
2. `02-business-invariants`: provenance, no unvalidated memory, PII redaction, state ownership.
3. `03-state-machine`: Memory Candidate, Memory Version, Knowledge Document ingestion, Deletion state machines.
4. `04-use-cases`: working memory update, knowledge ingestion, search, candidate extraction, validation, retention.
5. `05-api-contracts`: Runtime search API, admin ingestion API, memory candidate review API, deletion API.
6. `06-event-contracts`: consumed ticket/workflow/evaluation events and published memory/knowledge events.
7. `07-data-model`: PostgreSQL + pgvector tables, graph tables, indexes, unique keys, retention fields.
8. `08-transaction-and-outbox`: candidate writes, active memory publication, embedding generation, outbox order.
9. `09-concurrency-and-idempotency`: concurrent memory writes, duplicate events, document reingestion, search-log de-duplication.
10. `10-failure-handling`: embedding failure, poison documents, partial ingestion, degraded retrieval.
11. `11-security`: PII/secret redaction, tenant/role filters, knowledge ACLs, audit.
12. `12-observability`: retrieval precision, hit rate, index lag, candidate acceptance rate, traces.
13. `13-package-and-class-design`: service packages, ports, adapters, repositories, pipeline.
14. `14-testing-strategy`: unit, integration, contract, retrieval-quality, security, and recovery tests.

## Relationship With Other Domains

- `02-ticket-workflow`: emits resolved/closed/reopened facts. 04 reads those facts but never changes the ticket lifecycle.
- `03-agent-runtime-orchestration`: calls search / working-memory APIs to build agent context. 04 does not advance workflow state.
- `05-tool-gateway-mediation`: Tool Gateway may provide tool results as evidence sources, but 04 does not execute tools.
- `06-policy-approval-governance`: 04 calls policy / redaction / retention rules and does not approve high-risk memories by itself.
- `07-evaluation-improvement`: evaluation results may affect memory usefulness scores but cannot bypass validation to mutate active memory.
