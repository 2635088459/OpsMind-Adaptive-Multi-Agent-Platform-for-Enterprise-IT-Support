# 05 API Contracts

## Runtime API

### `POST /internal/memory/v1/search`

Request:

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

Response:

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

Graph paths are explanation and rerank input, not business actions. Runtime may include them in Agent context, but cannot use them to bypass Policy / Tool Gateway / Verification.

### `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`

Used by Runtime to update Working Memory. Requires `expectedVersion`.

## Admin API

### `POST /internal/memory/v1/admin/documents`

Ingest a knowledge document. Only admin / ingestion worker callers may use it.

Optional fields:

- `extractGraph`: whether to extract service / symptom / action graph entities from the document.
- `graphNamespace`: source namespace to avoid collisions between same-named entities from different systems.

### `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`

Approve a memory candidate and trigger publication.

### `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`

Reject a candidate and record the reason.

### `POST /internal/memory/v1/admin/memories/{memoryId}/deprecate`

Mark an active memory as deprecated so it is excluded from default Agent retrieval.

### `POST /internal/memory/v1/admin/deletion-requests`

Create a deletion request. Execution requires policy / authorization.

### `GET /internal/memory/v1/admin/graph/nodes/{nodeId}`

Query a graph node, adjacent edges, and sources. Admin/debug only; not exposed to Agents by default.

## Error Codes

- `MEMORY_VALIDATION_FAILED`
- `WORKING_MEMORY_VERSION_CONFLICT`
- `RETRIEVAL_ACCESS_DENIED`
- `DOCUMENT_ALREADY_INGESTED`
- `DOCUMENT_INGESTION_FAILED`
- `MEMORY_CANDIDATE_CONFLICTING`
- `MEMORY_DEGRADED`
- `DELETION_NOT_AUTHORIZED`

## API Principles

- Return redacted snippets to Runtime, not raw documents.
- Return provenance to Agents without exposing every internal scoring detail.
- Admin APIs must write audit records.
- Search APIs do not change Memory state; they only write retrieval logs.
