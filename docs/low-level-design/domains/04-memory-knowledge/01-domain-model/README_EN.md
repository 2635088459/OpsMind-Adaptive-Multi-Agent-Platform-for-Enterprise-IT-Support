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

### KnowledgeGraph

Memory Knowledge maintains a lightweight graph that connects entities and evidence across sources. Graph is a retrieval index and explanation layer; it does not own Ticket / Workflow / Tool state.

Core node types:

- `TICKET`
- `WORKFLOW`
- `MEMORY`
- `MEMORY_VERSION`
- `DOCUMENT`
- `DOCUMENT_CHUNK`
- `SERVICE`
- `APPLICATION`
- `SYMPTOM`
- `ROOT_CAUSE`
- `ACTION`
- `OWNER`
- `TOOL_EVIDENCE`
- `POLICY_RULE`
- `VERIFICATION_OUTCOME`

Core edge types:

- `MENTIONS`: a document / memory mentions an entity.
- `SUPPORTED_BY`: a root cause / action is supported by evidence.
- `RESOLVED_BY`: a symptom / failure mode was resolved by an action.
- `AFFECTS`: a symptom affects an application / service.
- `OWNED_BY`: a service / application belongs to an owner.
- `SIMILAR_TO`: a memory / ticket is similar to another one.
- `DERIVED_FROM`: a memory version derives from a ticket / workflow / document chunk.
- `CONFLICTS_WITH`: a memory conflicts with another memory or document chunk.
- `SUPERSEDES`: a memory version replaces an older version.

### GraphNode

Fields:

- `nodeId`
- `nodeType`
- `stableKey`
- `displayName`
- `properties`
- `classification`
- `sourceRefs`
- `createdAt`

`stableKey` prevents duplicate entities, for example `service:vpn-auth` or `symptom:mfa-loop-after-reset`.

### GraphEdge

Fields:

- `edgeId`
- `edgeType`
- `fromNodeId`
- `toNodeId`
- `confidence`
- `evidenceRefs`
- `properties`
- `createdAt`

GraphEdge must have evidenceRefs; relationships cannot be created without evidence.

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
- `graphPaths`
- `latencyMs`
- `createdAt`

## Value Objects

- `SourceRef`: `sourceType + sourceId + version + fieldPath`.
- `EmbeddingRef`: `provider + model + dimensions + vectorId`.
- `RetrievalScore`: semantic, keyword, recency, trust, success, and human-validation components.
- `RedactionReport`: redacted fields, secret patterns, and policy rule ids.
- `AccessScope`: tenant, application, queue, role, and classification.
- `GraphPath`: `nodeIds + edgeIds + pathScore + explanation`.
- `EntityKey`: `nodeType + normalizedName + namespace`.

## Domain Boundary

Memory Knowledge may store references and summaries, but it must never become the system of record for ticket state, workflow state, policy decisions, or tool execution.

Edges in the graph are not final truth by themselves. They are evidence-backed explainable indexes; executing actions, closing tickets, or approving policy must go back to the owning domain.
