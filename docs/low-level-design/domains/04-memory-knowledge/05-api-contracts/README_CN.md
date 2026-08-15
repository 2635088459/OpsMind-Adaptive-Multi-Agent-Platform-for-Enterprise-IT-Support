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
    "maxResults": 8
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
      }
    }
  ]
}
```

### `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`

用于 Runtime 更新 Working Memory。必须传 `expectedVersion`。

## Admin API

### `POST /internal/memory/v1/admin/documents`

导入 knowledge document。仅 admin / ingestion worker 可调用。

### `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`

批准候选 memory 并触发 publish。

### `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`

拒绝候选并记录原因。

### `POST /internal/memory/v1/admin/memories/{memoryId}/deprecate`

将 active memory 标记为 deprecated，默认不再返回给 Agent。

### `POST /internal/memory/v1/admin/deletion-requests`

创建删除请求。执行前必须通过 policy / authorization。

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
