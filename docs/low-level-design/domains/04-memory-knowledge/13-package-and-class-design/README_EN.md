# 13 Package And Class Design

## Service Boundary

Recommended service name: `memory-knowledge-service`.

MVP technology stack follows the shared baseline:

- Python;
- FastAPI;
- PostgreSQL + pgvector;
- RabbitMQ;
- OpenTelemetry.

## Package Structure

```text
memoryknowledge/
  domain/
    working_memory.py
    memory.py
    memory_candidate.py
    knowledge_document.py
    retrieval.py
    events.py
  application/
    commands.py
    records.py
    ports_in.py
    ports_out.py
    services/
      update_working_memory.py
      search_memory.py
      ingest_document.py
      extract_memory_candidate.py
      validate_memory_candidate.py
      publish_memory.py
      execute_retention.py
      dispatch_outbox_events.py
  infrastructure/
    persistence/
      postgres/
      in_memory.py
    embedding/
    retrieval/
    event_publisher_rabbitmq.py
    redaction.py
    document_parser.py
  interfaces/
    rest/
    admin/
    event/
    errors.py
  settings.py
  container.py
  main.py
```

## Ports

Input ports:

- `SearchMemoryUseCase`
- `UpdateWorkingMemoryUseCase`
- `IngestKnowledgeDocumentUseCase`
- `ExtractMemoryCandidateUseCase`
- `ValidateMemoryCandidateUseCase`
- `PublishMemoryUseCase`
- `ExecuteRetentionUseCase`

Output ports:

- `WorkingMemoryRepository`
- `MemoryCandidateRepository`
- `MemoryRepository`
- `KnowledgeDocumentRepository`
- `EmbeddingRepository`
- `RetrievalLogRepository`
- `ProcessedEventRepository`
- `OutboxRepository`
- `EmbeddingProvider`
- `RedactionPolicyPort`
- `AuthorizationPort`
- `TicketSnapshotPort`
- `WorkflowTracePort`
- `EventPublisherPort`

## Class Design Principles

- Domain objects do not depend on FastAPI, SQLAlchemy, RabbitMQ, or LLM SDKs.
- Application services orchestrate transactions and ports.
- Infrastructure adapters handle external systems and database details.
- Interface mappers convert wire shapes into commands.
- Retrieval scorer and redactor must be unit-testable.

## Relationship With 03

Agent Runtime calls 04 only through API / client boundaries, never by reading 04 tables directly. 04 returns evidence, not executable actions.
