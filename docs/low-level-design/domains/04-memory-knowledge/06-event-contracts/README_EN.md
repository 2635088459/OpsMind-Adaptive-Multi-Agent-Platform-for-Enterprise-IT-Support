# 06 Event Contracts

## Consumed Events

### `ticket.resolved.v1`

Purpose: trigger episodic / procedural memory candidate extraction.

Key fields:

- `eventId`
- `ticketId`
- `ticketCycleId`
- `resolutionSummary`
- `resolvedBy`
- `resolvedAt`
- `verificationStatus`

### `ticket.closed.v1`

Purpose: confirm outcome and adjust candidate usefulness.

Key fields: `ticketId`, `closedAt`, `closeReason`, `requesterConfirmed`.

### `workflow.completed.v1`

Purpose: obtain automation trace, task summaries, and tool evidence refs.

Key fields: `workflowInstanceId`, `ticketId`, `ticketCycleId`, `completedAt`, `taskResults`.

### `workflow.failed.v1`

Purpose: record failure experience, but do not auto-publish procedural memory by default.

### `evaluation.completed.v1`

Purpose: update memory usefulness score, retrieval precision, and candidate quality.

### `policy.retention.changed.v1`

Purpose: recompute retention and visibility for memories/documents.

## Published Events

### `memory.candidate.created.v1`

Published when a candidate is extracted and enters the pipeline.

### `memory.candidate.rejected.v1`

Published when a candidate is rejected due to PII, insufficient evidence, duplication, or conflict.

### `memory.published.v1`

Published after an active memory version is created.

### `memory.superseded.v1`

Published when an old version is replaced by a new version.

### `memory.deleted.v1`

Published after a memory or document becomes deleted / non-retrievable.

### `knowledge.document.indexed.v1`

Published after document ingestion completes and becomes searchable.

### `knowledge.graph.updated.v1`

Published after graph nodes / edges are updated by ingestion or memory publication. This event is for evaluation / observability and must not drive Ticket or Workflow state.

Key fields:

- `graphUpdateId`
- `sourceType`
- `sourceId`
- `nodeCount`
- `edgeCount`
- `indexVersion`

## Envelope

All events use the shared envelope:

```json
{
  "eventId": "uuid",
  "eventType": "memory.published.v1",
  "producer": "memory-knowledge-service",
  "schemaVersion": 1,
  "aggregateId": "memory-id",
  "ticketId": "optional-ticket-id",
  "correlationId": "uuid",
  "causationId": "uuid",
  "occurredAt": "2026-08-10T00:00:00Z",
  "payload": {}
}
```

## Idempotency

All consumers must deduplicate by `eventId + consumerName`. For ticket/workflow events, candidate extraction must also use `sourceHash + memoryType` to prevent duplicate candidates under different event ids.
