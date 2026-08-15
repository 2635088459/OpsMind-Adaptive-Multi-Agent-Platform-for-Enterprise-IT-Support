# 14 Testing Strategy

## Unit Tests

- WorkingMemory merge and version conflict。
- MemoryCandidate 状态迁移。
- Redaction rule。
- SourceRef validation。
- Retrieval score composition。
- Graph node stable key normalization。
- Graph edge evidence validation。
- Bounded graph expansion。
- Conflict detection。
- Retention visibility。

## Application Tests

- update working memory 成功/版本冲突。
- search 返回 provenance。
- search 返回 graph path explanation。
- candidate extraction 幂等。
- duplicate candidate 不创建新 Memory。
- conflicting candidate 进入 review。
- publish memory supersedes old version。
- deletion request makes memory non-retrievable。

## Integration Tests

- PostgreSQL schema migration。
- pgvector nearest-neighbor search。
- full-text + vector hybrid retrieval。
- graph node / edge persistence and bounded traversal。
- RabbitMQ outbox publish/replay。
- document ingestion partial recovery。
- embedding provider fake adapter。

## Contract Tests

消费事件：

- `ticket.resolved.v1`
- `ticket.closed.v1`
- `workflow.completed.v1`
- `workflow.failed.v1`
- `evaluation.completed.v1`

发布事件：

- `memory.candidate.created.v1`
- `memory.candidate.rejected.v1`
- `memory.published.v1`
- `memory.superseded.v1`
- `memory.deleted.v1`
- `knowledge.document.indexed.v1`
- `knowledge.graph.updated.v1`

## Security Tests

- PII 不进入 active memory。
- secret 不出现在 logs / retrieval response。
- role / ACL filter 生效。
- prompt injection 文档不能覆盖 runtime instruction。
- deletion 后 search 不返回对象。

## Retrieval Quality Tests

MVP 使用小型 deterministic fixture：

- known query 应命中对应 runbook。
- 相似历史 ticket 应排在无关 ticket 前。
- expired/deprecated memory 不返回。
- source trust 影响排序。
- human validated memory 优先。
- graph path 能把相似 ticket、symptom、action 解释清楚。
- graph expansion 不跨越 ACL / classification。

## Recovery Tests

- duplicate event redelivery。
- embedding failure retry。
- document ingestion crash after chunking。
- outbox dead-letter replay。
- retention partial failure resume。
- graph upsert retry after ingestion crash。

## 完成标准

04 进入实现前，至少每个 phase/spec 都能映射到上述测试类型之一；实现后，单元、应用、契约测试必须不依赖外部网络。
