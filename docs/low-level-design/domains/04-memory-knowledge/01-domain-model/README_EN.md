# 01 Domain Model

## Domain Goal

The Memory Knowledge model separates current context from long-term knowledge assets. Current context supports an active Workflow. Long-term knowledge supports future retrieval, reuse, and evaluation.

## Aggregates

### WorkingMemory

Short-lived, mergeable ticket/workflow context.

Fields:

- `workingMemoryId`
- `ticketId`
- `ticketCycleId`
- `workflowInstanceId`
- `version`
- `facts`
- `hypotheses`
- `rejectedHypotheses`
- `completedTasks`
- `pendingTasks`
- `toolEvidenceRefs`
- `approvalDecisionRefs`
- `contextSummary`
- `updatedBy`
- `updatedAt`

Rules:

- Scope is `ticketId + ticketCycleId + workflowInstanceId`.
- Updates must use optimistic versioning.
- Raw secrets, credentials, and unredacted tool output are forbidden in body fields.

### MemoryCandidate

A proposed long-term memory extracted from tickets, workflows, tool evidence, human feedback, or evaluation.

States:

- `EXTRACTED`
- `REDACTED`
- `VALIDATED`
- `DUPLICATE`
- `CONFLICTING`
- `APPROVED`
- `REJECTED`
- `PUBLISHED`

Fields:

- `candidateId`
- `memoryType`
- `sourceRefs`
- `candidateText`
- `redactedText`
- `confidenceScore`
- `usefulnessScore`
- `conflictSetId`
- `reviewRequired`
- `createdAt`

### Memory

The logical identity of a long-term memory. Its current content is represented by `MemoryVersion`.

Types:

- `EPISODIC`: one ticket's symptoms, evidence, root cause, actions, and outcome.
- `SEMANTIC`: stable facts generalized from multiple sources.
- `PROCEDURAL`: reusable troubleshooting steps, runbook fragments, and decision paths.
- `ORGANIZATIONAL`: ownership, escalation paths, and dependencies.
- `AGENT_PERFORMANCE`: agent strengths, cost, latency, and error patterns.

### MemoryVersion

An immutable version of long-term memory content.

Fields:

- `memoryVersionId`
- `memoryId`
- `version`
- `status`
- `content`
- `summary`
- `embeddingRef`
- `sourceHash`
- `supersedesVersionId`
- `createdBy`
- `createdAt`

### KnowledgeDocument

A formal knowledge source such as a runbook, SOP, FAQ, advisory, or service catalog entry.

Fields:

- `documentId`
- `sourceSystem`
- `externalId`
- `title`
- `documentType`
- `acl`
- `version`
- `ingestionStatus`
- `contentHash`
- `effectiveFrom`
- `expiresAt`

### DocumentChunk

A retrieval chunk derived from a KnowledgeDocument.

Fields:

- `chunkId`
- `documentId`
- `chunkIndex`
- `content`
- `tokenCount`
- `headingPath`
- `contentHash`
- `embeddingRef`

### RetrievalLog

Auditable record of every retrieval.

Fields:

- `retrievalId`
- `requesterType`
- `requesterId`
- `ticketId`
- `workflowInstanceId`
- `queryHash`
- `filters`
- `resultRefs`
- `latencyMs`
- `createdAt`

## Value Objects

- `SourceRef`: `sourceType + sourceId + version + fieldPath`.
- `EmbeddingRef`: `provider + model + dimensions + vectorId`.
- `RetrievalScore`: semantic, keyword, recency, trust, success, and human-validation components.
- `RedactionReport`: redacted fields, secret patterns, and policy rule ids.
- `AccessScope`: tenant, application, queue, role, and classification.

## Domain Boundary

Memory Knowledge may store references and summaries, but it must never become the system of record for ticket state, workflow state, policy decisions, or tool execution.
