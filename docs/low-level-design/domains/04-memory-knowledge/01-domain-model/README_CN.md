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
- `latencyMs`
- `createdAt`

## 值对象

- `SourceRef`：`sourceType + sourceId + version + fieldPath`。
- `EmbeddingRef`：`provider + model + dimensions + vectorId`。
- `RetrievalScore`：semantic、keyword、recency、trust、success、humanValidation 的分项分数。
- `RedactionReport`：redacted fields、secret patterns、policy rule ids。
- `AccessScope`：tenant、application、queue、role、classification。

## 领域边界

Memory Knowledge 可以保存引用和摘要，但不能成为 ticket state、workflow state、policy decision 或 tool execution 的系统事实来源。
