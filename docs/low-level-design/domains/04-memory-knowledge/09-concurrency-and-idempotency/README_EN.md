# 09 Concurrency And Idempotency

## Idempotency Keys

- Event consumer: `eventId + consumerName`.
- Candidate extraction: `sourceHash + memoryType`.
- Document ingestion: `sourceSystem + externalId + version`.
- Graph node upsert: `nodeType + stableKey`.
- Graph edge upsert: `fromNodeId + toNodeId + edgeType + sourceHash`.
- Working memory update: `workingMemoryId + expectedVersion`.
- Memory publication: `candidateId`.
- Deletion request: `requestId`.

## Working Memory Concurrency

WorkingMemory uses optimistic locking:

- The caller submits `expectedVersion`.
- Version mismatch returns `WORKING_MEMORY_VERSION_CONFLICT`.
- Runtime may reload and merge the patch.
- Last-write-wins overwrites are forbidden.

## Candidate Concurrency

Multiple events may trigger candidate extraction for the same source:

- `sourceHash + memoryType` unique constraint prevents duplicates.
- Duplicate requests return the existing candidate.
- Validation workers must lock the candidate row.

## Publish Concurrency

A Memory can have only one active version:

- Publishing locks the Memory row.
- Active version is protected by a partial unique index.
- The losing concurrent publish must retry or become a conflict.

## Document Reingestion

- Reingesting the same document version is idempotent success.
- A new version creates a new document row and new chunks.
- Old documents may become `SUPERSEDED`, but old retrieval logs are not rewritten.

## Retrieval Concurrency

- Search is stateless and uses the currently visible index.
- RetrievalLog is append-only.
- During index updates, returning an old active version is allowed but must include index version.
- Graph expansion uses the currently visible graph snapshot.
- During graph edge updates, returning an old path is allowed, but retrieval log must record graph index version.

## Graph Concurrency

- Node upsert must use unique keys to avoid duplicates.
- Edge upsert must use sourceHash to avoid duplicates.
- When hiding/deleting a graph node, do not physically delete edges; edge visibility is determined by both edge status and source visibility.
- Traversal must set max depth and max nodes to avoid oversized result sets during concurrent updates.

## Outbox Idempotency

Consumers must tolerate duplicate `memory.published.v1`. The event payload contains `memoryId + memoryVersionId`; downstream consumers deduplicate with that pair.
