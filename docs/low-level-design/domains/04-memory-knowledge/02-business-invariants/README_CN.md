# 02 Business Invariants

## 状态所有权

- Ticket 状态只由 `02-ticket-workflow` 拥有。
- Workflow 状态只由 `03-agent-runtime-orchestration` 拥有。
- Tool execution 事实只由 `05-tool-gateway-mediation` 拥有。
- Memory Knowledge 只拥有 memory、knowledge、retrieval 和 provenance。

## 记忆写入不变量

- `MemoryCandidate` 不能作为检索结果返回给 Agent，除非其状态已经 `PUBLISHED`。
- Active `MemoryVersion` 必须至少有一个 `SourceRef`。
- 长期记忆必须保存 redaction report。
- 长期记忆必须有 `confidenceScore` 和 `sourceTrustScore`。
- `DUPLICATE` candidate 不能创建新的 Memory，只能链接到既有 Memory。
- `CONFLICTING` candidate 必须人工或 policy 处理，不能自动覆盖 active memory。

## 检索不变量

- 检索结果必须带 provenance。
- 检索必须应用 tenant、role、classification 和 document ACL 过滤。
- Retrieval score 不能只依赖 embedding similarity。
- Graph expansion 不能绕过 ACL / classification filter。
- Graph edge 必须有 evidenceRefs 和 confidence，不能保存无来源关系。
- Graph path 返回给 Runtime 时必须能解释为什么相关。
- 已过期、已删除、已 superseded 且不可见的版本不能被返回。
- Agent 看到的是 redacted content，不是 raw source。

## Working Memory 不变量

- Working Memory 是短期状态，不等于长期记忆。
- Working Memory 更新必须带 expected version。
- 同一个 scope 只能有一个 active WorkingMemory。
- 被 reject 的 hypothesis 必须保留原因，避免重复调查。
- Tool evidence 只保存引用、摘要、状态和哈希，不保存敏感原始输出。

## Knowledge Document 不变量

- 同一 `sourceSystem + externalId + version` 只能 ingestion 一次。
- Document chunk 必须可追溯到 document version。
- reingestion 不能原地修改旧 chunks，必须创建新 document version。
- embedding model 或 chunking policy 变化必须记录 index version。
- document reingestion 产生的新 entity / edge 必须带 document version，不覆盖旧 version 的 graph provenance。

## Graph 不变量

- `stableKey + nodeType` 唯一，防止同一 service / symptom 被重复建点。
- `fromNodeId + toNodeId + edgeType + sourceHash` 唯一，防止同一证据重复建边。
- 删除 memory / document 时，相关 graph nodes / edges 必须同步变为不可检索或被 tombstone。
- `CONFLICTS_WITH` 边不能自动决定胜负，只能触发 candidate conflict 流程。
- graph traversal depth MVP 默认不超过 2，除非 admin/research API 明确提升。

## 安全不变量

- PII、secret、access token、完整用户标识不能进入 active memory content。
- 高敏 classification 的 memory 默认不可跨 queue / role 检索。
- 删除请求必须影响 retrieval visibility，不能只删除 metadata。
- 所有 admin override 必须审计。

## 退化模式

Memory unavailable 时，Agent Runtime 可以继续执行，但必须标记 degraded retrieval。不得因为检索不可用而伪造历史证据。
