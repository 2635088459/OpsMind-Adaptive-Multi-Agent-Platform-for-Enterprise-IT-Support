# 12 Observability

## Logs

Structured log fields:

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

Logs must not include raw query, raw document, raw memory content, secrets, or full tool output.

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

Search trace spans:

- request validation;
- access scope resolution;
- embedding query;
- vector search;
- keyword search;
- rerank;
- redaction;
- retrieval log write.

Ingestion trace spans:

- parse;
- chunk;
- redaction scan;
- embedding;
- index write;
- outbox write.

## Audit Events

Audit events do not replace business events. Audit records who/when/why; business events synchronize systems.

Audited actions:

- `ingest_document`
- `approve_candidate`
- `reject_candidate`
- `publish_memory`
- `supersede_memory`
- `delete_memory`
- `search_denied`

## Dashboards

MVP dashboard:

- search latency and error rate;
- degraded mode frequency;
- candidate pipeline backlog;
- document ingestion backlog;
- embedding failure rate;
- retrieval empty-result rate;
- accepted/rejected candidate ratio.

## Alerting

Alerts:

- search p95 latency exceeds threshold;
- degraded retrieval spike;
- embedding dead-letter > 0;
- candidate backlog exceeds threshold;
- unpublished outbox exceeds threshold;
- high-sensitivity access-denied spike.
