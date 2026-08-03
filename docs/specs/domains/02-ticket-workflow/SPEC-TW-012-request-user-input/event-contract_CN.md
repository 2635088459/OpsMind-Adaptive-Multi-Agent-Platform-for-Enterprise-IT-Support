# SPEC-TW-012 — 事件契约

事件：

```text
ticket.user-input-requested.v1
```

Payload：

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "requestId": "3d912886-9652-4d88-8a64-1297b50f14c7",
  "previousStatus": "IN_PROGRESS",
  "newStatus": "WAITING_FOR_USER",
  "requestedBy": "sam.support",
  "requestedAt": "2026-08-03T18:00:00Z",
  "waitingForRequesterSince": "2026-08-03T18:00:00Z",
  "resumeStatus": "IN_PROGRESS",
  "expiresAt": "2026-08-05T18:00:00Z"
}
```

事件不得包含完整 prompt 中的 secret、Authorization、原始 claims 或内部工具日志。
