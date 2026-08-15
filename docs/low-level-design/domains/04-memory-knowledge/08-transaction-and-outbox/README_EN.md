# 08 Transaction And Outbox

## Principles

- External event consumption, state updates, and outbox writes must happen in one database transaction.
- Embedding generation is an external side effect and is not part of the core business transaction.
- Search APIs only write retrieval logs and do not publish domain events.
- Active memory publication must persist the version before publishing the outbox event.

## Candidate Extraction Transaction

1. Verify the consumed event has not been processed.
2. Calculate `sourceHash`.
3. Upsert MemoryCandidate.
4. Write candidate source refs.
5. Mark the event as processed.
6. Write outbox `memory.candidate.created.v1`.
7. Commit.

## Candidate Validation Transaction

1. Lock candidate.
2. Write redacted text and redaction report.
3. Write validation / deduplication / conflict results.
4. Update candidate status.
5. If rejected, write outbox `memory.candidate.rejected.v1`.
6. Commit.

## Publish Memory Transaction

1. Lock candidate and target Memory.
2. Create MemoryVersion.
3. Mark the old active version as `SUPERSEDED`.
4. Mark the new version as `ACTIVE`.
5. Update Memory current version.
6. Mark candidate as `PUBLISHED`.
7. Upsert graph nodes / edges.
8. Write a `SUPERSEDES` edge for superseded versions and hide the old version from default retrieval.
9. Write outbox `memory.published.v1` and optional `memory.superseded.v1`.
10. Commit.

Embedding may be completed as a required step before publication, or an unembedded version may be published and completed through an async `embedding.pending` path. MVP should generate embeddings synchronously outside the short transaction, then enter the publish transaction after success.

## Document Ingestion Transaction

Each phase uses its own transaction:

- receive document metadata;
- parse result;
- batch insert chunks;
- update embedding refs;
- upsert graph nodes / edges;
- activate index;
- publish outbox.

This allows partial ingestion recovery without replaying the whole pipeline.

## Graph Upsert Transaction

Graph upsert is a sub-step of ingestion / publish; it does not decide business success by itself:

1. Extract entity candidates from redacted content.
2. Normalize to `nodeType + stableKey`.
3. Upsert `graph_nodes`.
4. Generate edges from relation candidates.
5. Every edge must carry sourceHash and evidenceRefs.
6. Upsert `graph_edges`.
7. Write graph node/edge ids back to retrieval metadata or version metadata.

If graph upsert fails:

- document ingestion does not enter `ACTIVE`;
- memory publication does not enter `PUBLISHED`;
- worker may retry;
- active memory without provenance graph is forbidden unless a spec explicitly allows sparse-only degraded mode.

## Outbox Publisher

- Publish at least once.
- Mark `PUBLISHED` after successful delivery.
- Move to `DEAD_LETTER` after retry exhaustion.
- Replay must be idempotent.

## Transaction Prohibitions

- Do not call LLMs inside a DB transaction.
- Do not call external document connectors inside a DB transaction.
- Do not wait on Agent Runtime inside a DB transaction.
- Do not create distributed transactions across 02/03/04.
