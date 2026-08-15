# 13 Package And Class Design

## 服务边界

推荐服务名：`memory-knowledge-service`。

MVP 技术栈沿用共享 baseline：

- Python；
- FastAPI；
- PostgreSQL + pgvector；
- RabbitMQ；
- OpenTelemetry。

## Package 结构

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

## 端口

Input ports：

- `SearchMemoryUseCase`
- `UpdateWorkingMemoryUseCase`
- `IngestKnowledgeDocumentUseCase`
- `ExtractMemoryCandidateUseCase`
- `ValidateMemoryCandidateUseCase`
- `PublishMemoryUseCase`
- `ExecuteRetentionUseCase`

Output ports：

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

## 类设计原则

- Domain object 不依赖 FastAPI、SQLAlchemy、RabbitMQ、LLM SDK。
- Application service 编排事务和端口。
- Infrastructure adapter 处理外部系统和数据库细节。
- Interface mapper 负责 wire shape 与 command 的转换。
- Retrieval scorer 与 redactor 可单测。

## 与 03 的调用关系

Agent Runtime 只通过 API / client 调用 04，不直接访问 04 数据表。04 返回 evidence，不返回可执行 action。
