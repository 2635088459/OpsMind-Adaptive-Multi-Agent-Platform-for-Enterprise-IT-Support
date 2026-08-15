# 07 Data Model

## Schema

使用 PostgreSQL + pgvector。逻辑 schema 为 `memory`。

## 表

### `memory.working_memory`

- `id uuid pk`
- `ticket_id uuid not null`
- `ticket_cycle_id uuid not null`
- `workflow_instance_id uuid not null`
- `version int not null`
- `body jsonb not null`
- `summary text`
- `status text not null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

唯一键：`ticket_id, ticket_cycle_id, workflow_instance_id, status` where status = `ACTIVE`。

### `memory.memory_candidates`

- `id uuid pk`
- `memory_type text not null`
- `status text not null`
- `source_hash text not null`
- `source_refs jsonb not null`
- `candidate_text text not null`
- `redacted_text text`
- `redaction_report jsonb`
- `confidence_score numeric`
- `usefulness_score numeric`
- `duplicate_of_memory_id uuid`
- `conflict_set_id uuid`
- `review_required boolean not null`
- `created_at timestamptz not null`

唯一键：`source_hash, memory_type`。

### `memory.memories`

- `id uuid pk`
- `memory_type text not null`
- `status text not null`
- `current_version_id uuid`
- `application_code text`
- `category text`
- `classification text not null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

### `memory.memory_versions`

- `id uuid pk`
- `memory_id uuid not null`
- `version int not null`
- `status text not null`
- `content text not null`
- `summary text`
- `source_refs jsonb not null`
- `redaction_report jsonb not null`
- `source_hash text not null`
- `supersedes_version_id uuid`
- `created_at timestamptz not null`

唯一键：`memory_id, version`。部分唯一索引：one `ACTIVE` version per `memory_id`。

### `memory.knowledge_documents`

- `id uuid pk`
- `source_system text not null`
- `external_id text not null`
- `version text not null`
- `title text not null`
- `document_type text not null`
- `classification text not null`
- `acl jsonb not null`
- `status text not null`
- `content_hash text not null`
- `raw_content_ref text`
- `created_at timestamptz not null`

唯一键：`source_system, external_id, version`。

### `memory.document_chunks`

- `id uuid pk`
- `document_id uuid not null`
- `chunk_index int not null`
- `content text not null`
- `content_hash text not null`
- `heading_path text`
- `token_count int not null`
- `status text not null`

唯一键：`document_id, chunk_index`。

### `memory.embeddings`

- `id uuid pk`
- `owner_type text not null`
- `owner_id uuid not null`
- `model text not null`
- `dimensions int not null`
- `embedding vector`
- `content_hash text not null`
- `created_at timestamptz not null`

索引：`ivfflat` 或 `hnsw` vector index，按 MVP 数据量选择。

### `memory.retrieval_logs`

- `id uuid pk`
- `requester_type text not null`
- `requester_id text not null`
- `ticket_id uuid`
- `workflow_instance_id uuid`
- `query_hash text not null`
- `filters jsonb not null`
- `result_refs jsonb not null`
- `degraded boolean not null`
- `latency_ms int not null`
- `created_at timestamptz not null`

### `memory.processed_events` / `memory.outbox_events`

沿用 02/03 模式：consumer 幂等表和 outbox 发布表。

## Retention

所有可检索对象必须有 visibility 状态。删除优先让对象不可检索，再异步清理 embeddings / chunks。
