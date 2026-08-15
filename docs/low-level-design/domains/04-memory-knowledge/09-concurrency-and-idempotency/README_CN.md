# 09 Concurrency And Idempotency

## 幂等键

- Event consumer：`eventId + consumerName`。
- Candidate extraction：`sourceHash + memoryType`。
- Document ingestion：`sourceSystem + externalId + version`。
- Graph node upsert：`nodeType + stableKey`。
- Graph edge upsert：`fromNodeId + toNodeId + edgeType + sourceHash`。
- Working memory update：`workingMemoryId + expectedVersion`。
- Memory publish：`candidateId`。
- Deletion request：`requestId`。

## Working Memory 并发

WorkingMemory 使用 optimistic locking：

- caller 提交 `expectedVersion`。
- version 不匹配返回 `WORKING_MEMORY_VERSION_CONFLICT`。
- Runtime 可以重新读取后 merge patch。
- 不允许 last-write-wins 覆盖。

## Candidate 并发

多个事件可能为同一个 source 触发候选抽取：

- `sourceHash + memoryType` 唯一约束防重复。
- 重复请求返回已有 candidate。
- validation worker 必须锁定 candidate row。

## Publish 并发

同一 Memory 只能有一个 active version：

- publish 时对 Memory 加行锁。
- active version 用部分唯一索引保护。
- 并发 publish 失败方必须重试或转为 conflict。

## Document Reingestion

- 相同 document version 重复导入是幂等成功。
- 新 version 创建新 document row 和新 chunks。
- 旧 document 可 `SUPERSEDED`，但旧 retrieval logs 不被改写。

## Retrieval 并发

- Search 无状态，结果基于当前 visible index。
- RetrievalLog append-only。
- index 更新期间允许返回旧 active version，但必须带 index version。
- graph expansion 基于当前 visible graph snapshot。
- graph edge 更新期间允许返回旧 path，但 retrieval log 必须记录 graph index version。

## Graph 并发

- node upsert 必须使用唯一键防重复。
- edge upsert 必须使用 sourceHash 防重复。
- 删除/隐藏 graph node 时，不物理删除 edge；edge visibility 通过 status 和 source visibility 共同决定。
- traversal 必须设置 max depth 和 max nodes，避免并发更新造成超大结果集。

## Outbox 幂等

消费者必须能处理重复 `memory.published.v1`。事件 payload 包含 `memoryId + memoryVersionId`，下游用该组合去重。
