# 10 Failure Handling

## Failure Classes

- `POISON_EVENT`: event payload cannot be parsed or misses key fields.
- `POISON_DOCUMENT`: unsupported format, empty content, excessive size, or unredactable secret.
- `EMBEDDING_FAILED`: embedding provider call failed.
- `INDEX_WRITE_FAILED`: vector or full-text index write failed.
- `VALIDATION_FAILED`: source evidence is insufficient or untrusted.
- `CONFLICT_DETECTED`: candidate conflicts with active memory.
- `RETRIEVAL_DEGRADED`: retrieval timed out or part of the index is unavailable.
- `RETENTION_FAILED`: deletion / expiration was not fully applied.

## Poison Event

- Record in a poison event table.
- Do not mark processed unless explicitly quarantined.
- Support admin replay.
- Do not create a candidate.

## Poison Document

- Move document state to `FAILED`.
- Store failure reason and redacted sample.
- Do not generate chunks / embeddings.
- Admin may correct metadata or content and retry.

## Embedding Failure

- Transient failure enters retry.
- Retry exhaustion moves the job to `DEAD_LETTER`.
- A knowledge document cannot become active before embedding.
- For memory publication, MVP should block publish; future versions may support sparse-only degraded indexing.

## Retrieval Degraded

Search API returns this for recoverable timeouts:

```json
{
  "degraded": true,
  "degradedReason": "VECTOR_INDEX_TIMEOUT",
  "results": []
}
```

Runtime continues execution, but the trace must record memory unavailable.

## Conflict Handling

Conflict is not an exception:

- Candidate enters `CONFLICTING`.
- A `memory_conflicts` record is created.
- Admin may reject, supersede, or create a separate memory.

## Recovery Workers

- ingestion recovery: scans stuck documents.
- embedding recovery: scans pending / retryable failed jobs.
- outbox replay: scans unpublished events.
- retention recovery: scans partially applied deletions.

## Forbidden Recovery

- Do not create active memory when evidence is missing.
- Do not publish a failed workflow as successful procedural memory.
- Do not use a fallback LLM to recreate source facts.
