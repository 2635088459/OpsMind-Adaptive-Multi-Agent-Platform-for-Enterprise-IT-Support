# 12 Observability

## Logs

结构化日志字段：

- `traceId`
- `correlationId`
- `ticketId`
- `workflowInstanceId`
- `retrievalId`
- `candidateId`
- `documentId`
- `memoryId`
- `eventType`
- `status`

禁止在日志中输出 raw query、raw document、raw memory content、secret 或完整 tool output。

## Metrics

- `memory_search_requests_total`
- `memory_search_latency_ms`
- `memory_search_degraded_total`
- `memory_retrieval_hit_rate`
- `memory_retrieval_empty_total`
- `memory_candidate_created_total`
- `memory_candidate_approved_total`
- `memory_candidate_rejected_total`
- `memory_conflict_detected_total`
- `knowledge_document_ingestion_latency_ms`
- `knowledge_embedding_failure_total`
- `memory_outbox_backlog`

## Traces

Search trace spans：

- request validation；
- access scope resolution；
- embedding query；
- vector search；
- keyword search；
- rerank；
- redaction；
- retrieval log write。

Ingestion trace spans：

- parse；
- chunk；
- redaction scan；
- embedding；
- index write；
- outbox write。

## Audit Events

Audit event 不替代 business event。Audit 用于 who/when/why，business event 用于系统间同步。

审计动作：

- `ingest_document`
- `approve_candidate`
- `reject_candidate`
- `publish_memory`
- `supersede_memory`
- `delete_memory`
- `search_denied`

## Dashboards

MVP dashboard：

- search latency and error rate；
- degraded mode frequency；
- candidate pipeline backlog；
- document ingestion backlog；
- embedding failure rate；
- retrieval empty-result rate；
- accepted/rejected candidate ratio。

## Alerting

告警：

- search p95 latency 超阈值；
- degraded retrieval 激增；
- embedding dead-letter > 0；
- candidate backlog 超阈值；
- outbox unpublished 超阈值；
- high sensitivity access denied 激增。
