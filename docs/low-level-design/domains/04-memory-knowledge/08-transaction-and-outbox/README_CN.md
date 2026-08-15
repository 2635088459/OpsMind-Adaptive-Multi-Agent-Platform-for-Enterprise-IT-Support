# 08 Transaction And Outbox

## 原则

- 外部事件消费、状态更新和 outbox 写入必须在一个数据库事务中完成。
- Embedding 生成是外部副作用，不放在核心业务事务里。
- Search API 只写 retrieval log，不发布领域事件。
- Active memory 发布必须先持久化版本，再发布 outbox。

## Candidate Extraction Transaction

1. 验证 consumed event 未处理。
2. 计算 `sourceHash`。
3. upsert MemoryCandidate。
4. 写 candidate source refs。
5. 标记 processed event。
6. 写 outbox `memory.candidate.created.v1`。
7. commit。

## Candidate Validation Transaction

1. 锁定 candidate。
2. 写 redacted text 和 redaction report。
3. 写 validation / dedup / conflict 结果。
4. 更新 candidate status。
5. 如 rejected，写 outbox `memory.candidate.rejected.v1`。
6. commit。

## Publish Memory Transaction

1. 锁定 candidate 和 target Memory。
2. 创建 MemoryVersion。
3. 将旧 active version 标记为 `SUPERSEDED`。
4. 将新 version 标记为 `ACTIVE`。
5. 更新 Memory current version。
6. 标记 candidate 为 `PUBLISHED`。
7. 写 outbox `memory.published.v1` 和可选 `memory.superseded.v1`。
8. commit。

Embedding 可以在 publish 前作为 required step 完成，也可以先发布无向量版本，再通过 `embedding.pending` 异步补齐；MVP 推荐同步生成 embedding 但放在短事务外，成功后再进入 publish transaction。

## Document Ingestion Transaction

每个阶段独立事务：

- receive document metadata；
- parse result；
- chunks batch insert；
- embedding refs update；
- index active；
- outbox publish。

这样可以恢复 partial ingestion，不需要重跑整个 pipeline。

## Outbox Publisher

- at-least-once publish。
- publish 成功后标记 `PUBLISHED`。
- retry 超限后进入 `DEAD_LETTER`。
- replay 必须幂等。

## Transaction 禁止事项

- 不在 DB transaction 内调用 LLM。
- 不在 DB transaction 内调用外部 document connector。
- 不在 DB transaction 内等待 Agent Runtime。
- 不跨 02/03/04 开分布式事务。
