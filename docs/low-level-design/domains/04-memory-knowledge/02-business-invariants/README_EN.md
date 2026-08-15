# 02 Business Invariants

## State Ownership

- Ticket state is owned only by `02-ticket-workflow`.
- Workflow state is owned only by `03-agent-runtime-orchestration`.
- Tool execution facts are owned only by `05-tool-gateway-mediation`.
- Memory Knowledge owns only memory, knowledge, retrieval, and provenance.

## Memory Write Invariants

- A `MemoryCandidate` must not be returned to Agents as retrieval output unless it is `PUBLISHED`.
- An active `MemoryVersion` must have at least one `SourceRef`.
- Long-term memory must store a redaction report.
- Long-term memory must have `confidenceScore` and `sourceTrustScore`.
- A `DUPLICATE` candidate cannot create a new Memory; it can only link to an existing one.
- A `CONFLICTING` candidate requires human or policy handling and cannot automatically overwrite active memory.

## Retrieval Invariants

- Retrieval results must carry provenance.
- Retrieval must apply tenant, role, classification, and document ACL filters.
- Retrieval score must not rely only on embedding similarity.
- Graph expansion must not bypass ACL / classification filters.
- Graph edges must have evidenceRefs and confidence; source-less relationships are forbidden.
- Graph paths returned to Runtime must explain why the result is related.
- Expired, deleted, superseded, and non-visible versions must not be returned.
- Agents see redacted content, never raw sources.

## Working Memory Invariants

- Working Memory is short-term state, not long-term memory.
- Working Memory updates must include an expected version.
- Only one active WorkingMemory exists for a given scope.
- Rejected hypotheses must retain reasons to avoid repeated investigation.
- Tool evidence stores references, summaries, status, and hashes, not sensitive raw output.

## Knowledge Document Invariants

- The same `sourceSystem + externalId + version` can be ingested only once.
- A document chunk must trace back to a document version.
- Reingestion must create a new document version instead of mutating old chunks in place.
- Embedding model or chunking-policy changes must be recorded as an index version.
- Document reingestion creates new entities / edges with the document version and does not overwrite old graph provenance.

## Graph Invariants

- `stableKey + nodeType` is unique to prevent duplicate service / symptom nodes.
- `fromNodeId + toNodeId + edgeType + sourceHash` is unique to prevent duplicate edges from the same evidence.
- When memory / document is deleted, related graph nodes / edges must become non-retrievable or tombstoned.
- `CONFLICTS_WITH` edges do not decide winners automatically; they trigger the candidate conflict flow.
- MVP graph traversal depth defaults to 2 unless an admin/research API explicitly raises it.

## Security Invariants

- PII, secrets, access tokens, and full user identifiers must not enter active memory content.
- High-sensitivity memory is not searchable across queue / role boundaries by default.
- Deletion requests must affect retrieval visibility, not only metadata.
- Every admin override must be audited.

## Degraded Mode

If Memory is unavailable, Agent Runtime may continue execution but must mark retrieval as degraded. It must not fabricate historical evidence because retrieval is down.
