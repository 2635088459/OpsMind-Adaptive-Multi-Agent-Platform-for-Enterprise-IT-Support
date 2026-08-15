# 10 Failure Handling

## Failure 分类

- `POISON_EVENT`：事件 payload 无法解析或缺少关键字段。
- `POISON_DOCUMENT`：文档格式不支持、内容为空、超过限制或含不可脱敏 secret。
- `EMBEDDING_FAILED`：embedding provider 调用失败。
- `INDEX_WRITE_FAILED`：向量或全文索引写入失败。
- `VALIDATION_FAILED`：source evidence 不足或不可信。
- `CONFLICT_DETECTED`：候选与 active memory 冲突。
- `RETRIEVAL_DEGRADED`：检索超时或部分索引不可用。
- `RETENTION_FAILED`：删除/过期处理未完全完成。

## Poison Event

- 记录 poison event 表。
- 不标记 processed，除非明确 quarantine。
- 支持 admin replay。
- 不创建 candidate。

## Poison Document

- document 状态进入 `FAILED`。
- 保存 failure reason 和 redacted sample。
- 不生成 chunks / embeddings。
- 可由 admin 修正 metadata 或 content 后重试。

## Embedding Failure

- 短暂失败进入 retry。
- 超限后标记 embedding job `DEAD_LETTER`。
- 对 knowledge document，未 embedded 前不可 active。
- 对 memory publish，MVP 推荐阻塞 publish；未来可支持 sparse-only degraded index。

## Retrieval Degraded

Search API 在可恢复超时时返回：

```json
{
  "degraded": true,
  "degradedReason": "VECTOR_INDEX_TIMEOUT",
  "results": []
}
```

Runtime 继续执行，但 trace 必须记录 memory unavailable。

## Conflict Handling

冲突不是异常：

- candidate 进入 `CONFLICTING`。
- 创建 `memory_conflicts` 记录。
- 可由 admin 选择 reject、supersede 或 create separate memory。

## Recovery Workers

- ingestion recovery：扫描 stuck document。
- embedding recovery：扫描 pending / failed retryable job。
- outbox replay：扫描 unpublished events。
- retention recovery：扫描 partially applied deletion。

## 不允许的恢复

- 不允许在 evidence 缺失时生成 active memory。
- 不允许把 failed workflow 自动发布为成功 procedural memory。
- 不允许用 fallback LLM 重新创造 source facts。
