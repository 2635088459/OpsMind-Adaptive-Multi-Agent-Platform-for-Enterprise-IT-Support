# 04 Use Cases

## UC-01 更新 Working Memory

触发：Agent Runtime 在 task 完成、tool result、approval result、verification result 后提交上下文更新。

流程：

1. 校验 caller 是 Runtime。
2. 读取当前 WorkingMemory。
3. 校验 expected version。
4. 对新增 facts / hypotheses / evidence refs 做脱敏检查。
5. 合并 patch。
6. 更新 summary 和 version。
7. 写 audit log。

## UC-02 检索知识与长期记忆

触发：Runtime / Knowledge Agent 请求 evidence。

流程：

1. 接收 query、ticket context、filters、access scope。
2. 对 query 做 secret 检测和 normalization。
3. 执行 hybrid retrieval：vector + keyword + metadata filters。
4. 根据 recency、source trust、resolution success、human validation rerank。
5. 返回 redacted snippets 和 provenance。
6. 写 RetrievalLog。

## UC-03 导入 Knowledge Document

触发：admin、seed job、connector 或 CI fixture。

流程：

1. 接收 document metadata 和 content。
2. 校验 ACL、classification、source version。
3. parse / normalize。
4. chunk。
5. redaction scan。
6. embedding。
7. index。
8. 发布 `knowledge.document.indexed.v1`。

## UC-04 从已解决 Ticket 抽取 Memory Candidate

触发：消费 `ticket.resolved.v1` 或 `ticket.closed.v1`。

流程：

1. 拉取 ticket summary、workflow trace、tool evidence refs、verification outcome。
2. 生成 candidate。
3. 脱敏。
4. 校验证据完整性。
5. 去重。
6. 冲突检测。
7. 打分。
8. 自动批准低风险高置信候选，或进入 review。

## UC-05 发布 Active Memory

触发：candidate 自动或人工 approved。

流程：

1. 创建 Memory 或定位 existing Memory。
2. 创建新的 MemoryVersion。
3. 生成 embedding。
4. 标记 previous active version 为 superseded。
5. 标记 candidate 为 published。
6. 写 outbox `memory.published.v1`。

## UC-06 删除或保留策略执行

触发：admin deletion request、retention scheduler、policy event。

流程：

1. 授权 deletion / retention action。
2. 找到受影响 memory、versions、chunks、embeddings、retrieval refs。
3. 应用 soft delete 或 hard redaction。
4. 清理 cache 和 search visibility。
5. 写 audit 和 `memory.deleted.v1`。

## UC-07 Memory 不可用降级

Runtime search 超时或 04 不可用时，调用方得到 `degraded=true`、空 results 和原因码。Runtime 继续执行，但必须在 workflow trace 中记录缺少 memory evidence。
