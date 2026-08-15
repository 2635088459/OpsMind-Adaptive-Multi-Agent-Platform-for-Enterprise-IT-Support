# 04 Use Cases

## UC-01 Update Working Memory

Trigger: Agent Runtime submits context updates after task completion, tool result, approval result, or verification result.

Flow:

1. Verify that the caller is Runtime.
2. Load the current WorkingMemory.
3. Check expected version.
4. Run redaction checks on new facts, hypotheses, and evidence refs.
5. Merge the patch.
6. Update summary and version.
7. Write an audit log.

## UC-02 Retrieve Knowledge and Long-Term Memory

Trigger: Runtime / Knowledge Agent requests evidence.

Flow:

1. Receive query, ticket context, filters, and access scope.
2. Run secret detection and normalization on the query.
3. Execute hybrid retrieval: vector + keyword + metadata filters.
4. Rerank by recency, source trust, resolution success, and human validation.
5. Return redacted snippets and provenance.
6. Write RetrievalLog.

## UC-03 Ingest Knowledge Document

Trigger: admin, seed job, connector, or CI fixture.

Flow:

1. Receive document metadata and content.
2. Validate ACL, classification, and source version.
3. Parse and normalize.
4. Chunk.
5. Run redaction scan.
6. Embed.
7. Index.
8. Publish `knowledge.document.indexed.v1`.

## UC-04 Extract Memory Candidate From Resolved Ticket

Trigger: consume `ticket.resolved.v1` or `ticket.closed.v1`.

Flow:

1. Fetch ticket summary, workflow trace, tool evidence refs, and verification outcome.
2. Generate candidate.
3. Redact.
4. Validate evidence completeness.
5. Deduplicate.
6. Detect conflicts.
7. Score.
8. Auto-approve low-risk high-confidence candidates or send to review.

## UC-05 Publish Active Memory

Trigger: candidate is automatically or manually approved.

Flow:

1. Create Memory or locate an existing Memory.
2. Create a new MemoryVersion.
3. Generate embedding.
4. Mark previous active version as superseded.
5. Mark candidate as published.
6. Write outbox `memory.published.v1`.

## UC-06 Execute Deletion or Retention

Trigger: admin deletion request, retention scheduler, or policy event.

Flow:

1. Authorize deletion / retention action.
2. Find affected memories, versions, chunks, embeddings, and retrieval refs.
3. Apply soft delete or hard redaction.
4. Clear cache and search visibility.
5. Write audit and `memory.deleted.v1`.

## UC-07 Degrade When Memory Is Unavailable

When Runtime search times out or 04 is down, callers receive `degraded=true`, empty results, and a reason code. Runtime continues execution but records missing memory evidence in the workflow trace.
