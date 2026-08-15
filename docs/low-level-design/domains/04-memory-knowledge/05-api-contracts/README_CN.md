# 05 API Contracts

## Runtime API

### `POST /internal/memory/v1/search`

请求：

```json
{
  "query": "vpn login fails after mfa reset",
  "ticketId": "uuid",
  "ticketCycleId": "uuid",
  "workflowInstanceId": "uuid",
  "requesterRole": "knowledge_agent",
  "filters": {
    "applicationCode": "VPN",
    "memoryTypes": ["EPISODIC", "PROCEDURAL"],
    "maxResults": 8,
    "includeGraphPaths": true,
    "maxGraphDepth": 2
  },
  "correlationId": "uuid"
}
```

响应：

```json
{
  "retrievalId": "uuid",
  "degraded": false,
  "results": [
    {
      "resultType": "MEMORY",
      "sourceId": "uuid",
      "sourceVersion": 3,
      "snippet": "Redacted evidence summary",
      "score": 0.87,
      "provenance": {
        "sourceType": "ticket",
        "sourceRef": "ticket:uuid",
        "redacted": true
      },
      "graphPaths": [
        {
          "pathScore": 0.76,
          "explanation": "Historical VPN incident shared symptom and resolving action.",
          "nodes": [
            {"nodeId": "uuid", "nodeType": "SYMPTOM", "displayName": "MFA loop after reset"},
            {"nodeId": "uuid", "nodeType": "ACTION", "displayName": "Reset stale device binding"}
          ],
          "edges": [
            {"edgeId": "uuid", "edgeType": "RESOLVED_BY", "confidence": 0.82}
          ]
        }
      ]
    }
  ]
}
```

Graph path 字段是解释和 rerank input，不是业务 action。Runtime 可以把它放进 Agent context，但不能据此绕过 Policy / Tool Gateway / Verification。

### `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`

用于 Runtime 更新 Working Memory。必须传 `expectedVersion`。

## Admin API

### `POST /internal/memory/v1/admin/documents`

导入 knowledge document。仅 admin / ingestion worker 可调用。

可选字段：

- `extractGraph`: 是否从文档中抽取 service / symptom / action 等 graph entities。
- `graphNamespace`: 文档来源命名空间，避免不同系统里的同名 entity 冲突。

### `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`

批准候选 memory 并触发 publish。

### `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`

拒绝候选并记录原因。

### `POST /internal/memory/v1/admin/memories/{memoryId}/deprecate`

将 active memory 标记为 deprecated，默认不再返回给 Agent。

### `POST /internal/memory/v1/admin/deletion-requests`

创建删除请求。执行前必须通过 policy / authorization。

### `GET /internal/memory/v1/admin/graph/nodes/{nodeId}`

查询 graph node、相邻边和来源。仅 admin/debug 使用，默认不暴露给 Agent。

## 错误码

- `MEMORY_VALIDATION_FAILED`
- `WORKING_MEMORY_VERSION_CONFLICT`
- `RETRIEVAL_ACCESS_DENIED`
- `DOCUMENT_ALREADY_INGESTED`
- `DOCUMENT_INGESTION_FAILED`
- `MEMORY_CANDIDATE_CONFLICTING`
- `MEMORY_DEGRADED`
- `DELETION_NOT_AUTHORIZED`

## API 原则

- 对 Runtime 返回 redacted snippet，不返回 raw document。
- 对 Agent 返回 provenance，不暴露内部评分全部细节。
- Admin API 必须写 audit。
- Search API 不改变 Memory 状态，只写 retrieval log。
