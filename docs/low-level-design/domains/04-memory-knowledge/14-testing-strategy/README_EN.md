# 14 Testing Strategy

## Unit Tests

- WorkingMemory merge and version conflict.
- MemoryCandidate state transitions.
- Redaction rules.
- SourceRef validation.
- Retrieval score composition.
- Conflict detection.
- Retention visibility.

## Application Tests

- update working memory success / version conflict.
- search returns provenance.
- candidate extraction is idempotent.
- duplicate candidate does not create a new Memory.
- conflicting candidate enters review.
- publish memory supersedes old version.
- deletion request makes memory non-retrievable.

## Integration Tests

- PostgreSQL schema migration.
- pgvector nearest-neighbor search.
- full-text + vector hybrid retrieval.
- RabbitMQ outbox publish/replay.
- document ingestion partial recovery.
- embedding provider fake adapter.

## Contract Tests

Consumed events:

- `ticket.resolved.v1`
- `ticket.closed.v1`
- `workflow.completed.v1`
- `workflow.failed.v1`
- `evaluation.completed.v1`

Published events:

- `memory.candidate.created.v1`
- `memory.candidate.rejected.v1`
- `memory.published.v1`
- `memory.superseded.v1`
- `memory.deleted.v1`
- `knowledge.document.indexed.v1`

## Security Tests

- PII does not enter active memory.
- Secrets do not appear in logs / retrieval response.
- Role / ACL filters apply.
- Prompt-injection documents cannot override runtime instructions.
- Search does not return deleted objects.

## Retrieval Quality Tests

MVP uses a small deterministic fixture:

- known query should hit the expected runbook.
- similar historical ticket should rank above unrelated ticket.
- expired/deprecated memory should not be returned.
- source trust affects ranking.
- human-validated memory ranks higher.

## Recovery Tests

- duplicate event redelivery.
- embedding failure retry.
- document ingestion crash after chunking.
- outbox dead-letter replay.
- retention partial failure resume.

## Completion Standard

Before 04 implementation starts, every phase/spec should map to at least one test type above. After implementation, unit, application, and contract tests must not depend on external network access.
