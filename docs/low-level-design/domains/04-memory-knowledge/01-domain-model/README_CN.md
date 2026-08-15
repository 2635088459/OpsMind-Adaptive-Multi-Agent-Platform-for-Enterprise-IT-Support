# 01 Domain Model

## 领域目标

Memory Knowledge 的领域模型把“当前上下文”和“长期知识资产”分开。当前上下文用于支持正在运行的 Workflow；长期知识资产用于未来检索、复用和评估。

## 聚合

### WorkingMemory

短期、可覆盖、可合并的 ticket/workflow 上下文。

字段：

- `workingMemoryId`
- `ticketId`
- `ticketCycleId`
- `workflowInstanceId`
- `version`
- `facts`
- `hypotheses`
- `rejectedHypotheses`
- `completedTasks`
- `pendingTasks`
- `toolEvidenceRefs`
- `approvalDecisionRefs`
- `contextSummary`
- `updatedBy`
- `updatedAt`

约束：

- scope 必须是 `ticketId + ticketCycleId + workflowInstanceId`。
- 更新必须使用 optimistic version。
- raw secret、完整凭据、未脱敏工具输出不能进入正文。

### MemoryCandidate

从 ticket、workflow、tool evidence、human feedback 或 evaluation 中抽取出的候选长期记忆。

状态：

- `EXTRACTED`
- `REDACTED`
- `VALIDATED`
- `DUPLICATE`
- `CONFLICTING`
- `APPROVED`
- `REJECTED`
- `PUBLISHED`

字段：

- `candidateId`
- `memoryType`
- `sourceRefs`
- `candidateText`
- `redactedText`
- `confidenceScore`
- `usefulnessScore`
- `conflictSetId`
- `reviewRequired`
- `createdAt`

### Memory

长期记忆的逻辑身份。当前有效内容由 `MemoryVersion` 表达。

类型：

- `EPISODIC`：一次具体 ticket 的症状、证据、根因、动作、结果。
- `SEMANTIC`：从多个事件中归纳的稳定事实。
- `PROCEDURAL`：可复用排障步骤、runbook 片段、决策路径。
- `ORGANIZATIONAL`：系统归属、升级路径、依赖关系。
- `AGENT_PERFORMANCE`：Agent 表现、成本、延迟、错误模式。

### MemoryVersion

长期记忆的不可变版本。

字段：

- `memoryVersionId`
- `memoryId`
- `version`
- `status`
- `content`
- `summary`
- `embeddingRef`
- `sourceHash`
- `supersedesVersionId`
- `createdBy`
- `createdAt`

### KnowledgeDocument

正式知识源，例如 runbook、SOP、FAQ、故障公告、服务目录。

字段：

- `documentId`
- `sourceSystem`
- `externalId`
- `title`
- `documentType`
- `acl`
- `version`
- `ingestionStatus`
- `contentHash`
- `effectiveFrom`
- `expiresAt`

### DocumentChunk

KnowledgeDocument 的检索切片。

字段：

- `chunkId`
- `documentId`
- `chunkIndex`
- `content`
- `tokenCount`
- `headingPath`
- `contentHash`
- `embeddingRef`

### KnowledgeGraph

Memory Knowledge 内部维护一个轻量 graph，用来连接不同来源中的实体与证据。Graph 是检索索引与解释层，不拥有 Ticket / Workflow / Tool 状态。

核心节点类型：

- `TICKET`
- `WORKFLOW`
- `MEMORY`
- `MEMORY_VERSION`
- `DOCUMENT`
- `DOCUMENT_CHUNK`
- `SERVICE`
- `APPLICATION`
- `SYMPTOM`
- `ROOT_CAUSE`
- `ACTION`
- `OWNER`
- `TOOL_EVIDENCE`
- `POLICY_RULE`
- `VERIFICATION_OUTCOME`

核心边类型：

- `MENTIONS`：document / memory 提到某个 entity。
- `SUPPORTED_BY`：root cause / action 被 evidence 支持。
- `RESOLVED_BY`：symptom / failure mode 被 action 解决。
- `AFFECTS`：symptom 影响 application / service。
- `OWNED_BY`：service / application 归属 owner。
- `SIMILAR_TO`：memory / ticket 相似。
- `DERIVED_FROM`：memory version 来源于 ticket / workflow / document chunk。
- `CONFLICTS_WITH`：memory 与 memory 或 document chunk 冲突。
- `SUPERSEDES`：memory version 取代旧版本。

### GraphNode

字段：

- `nodeId`
- `nodeType`
- `stableKey`
- `displayName`
- `properties`
- `classification`
- `sourceRefs`
- `createdAt`

`stableKey` 用于避免重复实体，例如 `service:vpn-auth` 或 `symptom:mfa-loop-after-reset`。

### GraphEdge

字段：

- `edgeId`
- `edgeType`
- `fromNodeId`
- `toNodeId`
- `confidence`
- `evidenceRefs`
- `properties`
- `createdAt`

GraphEdge 必须有 evidenceRefs，不能凭空建立关系。

### RetrievalLog

每次检索的可审计记录。

字段：

- `retrievalId`
- `requesterType`
- `requesterId`
- `ticketId`
- `workflowInstanceId`
- `queryHash`
- `filters`
- `resultRefs`
- `graphPaths`
- `latencyMs`
- `createdAt`

## 值对象

- `SourceRef`：`sourceType + sourceId + version + fieldPath`。
- `EmbeddingRef`：`provider + model + dimensions + vectorId`。
- `RetrievalScore`：semantic、keyword、recency、trust、success、humanValidation 的分项分数。
- `RedactionReport`：redacted fields、secret patterns、policy rule ids。
- `AccessScope`：tenant、application、queue、role、classification。
- `GraphPath`：`nodeIds + edgeIds + pathScore + explanation`。
- `EntityKey`：`nodeType + normalizedName + namespace`。

## 领域边界

Memory Knowledge 可以保存引用和摘要，但不能成为 ticket state、workflow state、policy decision 或 tool execution 的系统事实来源。

Graph 中的边也不是最终事实本身。它们只是带 evidence 的可解释索引；需要执行 action、关闭 ticket 或批准 policy 时，必须回到对应域。
