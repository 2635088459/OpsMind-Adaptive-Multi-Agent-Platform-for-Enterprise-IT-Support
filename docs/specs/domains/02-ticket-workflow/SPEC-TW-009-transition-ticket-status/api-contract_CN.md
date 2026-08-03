# SPEC-TW-009 — API 契约

## 1. 通用 Headers

```http
Authorization: Bearer <token>
Content-Type: application/json
If-Match: "<positive-version>"
Idempotency-Key: <8-to-128-character-key>
X-Correlation-ID: <optional-UUID>
```

服务端从认证上下文推导 actor identity 与授权队列。客户端不得在 body 中传入 actor、tenant 或 queue scope。

## 2. Transition Status

```http
POST /api/v1/tickets/{ticketId}/status-transitions
```

### 2.1 Start Work

```json
{
  "targetStatus": "IN_PROGRESS",
  "reason": "Starting endpoint investigation"
}
```

要求 Ticket 当前为 `ASSIGNED` 且已有负责人。

### 2.2 Wait for User

```json
{
  "targetStatus": "WAITING_FOR_USER",
  "reason": "Requester must provide device serial number",
  "waitingForRequesterSince": "2026-07-31T18:35:00Z"
}
```

`waitingForRequesterSince` 可省略，省略时服务端使用命令发生时间。

### 2.3 Wait for Approval

```json
{
  "targetStatus": "WAITING_FOR_APPROVAL",
  "reason": "Privileged remediation requires manager approval",
  "approvalReference": "approval-req-20260731-018"
}
```

`approvalReference` 必填，长度 3 到 128。

### 2.4 Resume Work

```json
{
  "targetStatus": "IN_PROGRESS",
  "reason": "Requester provided the missing device details"
}
```

要求 Ticket 当前为 `WAITING_FOR_USER` 或 `WAITING_FOR_APPROVAL`。

## 3. 成功响应

```http
HTTP/1.1 200 OK
ETag: "14"
Location: /api/v1/tickets/6c2ad02e-c394-41fb-8e38-dfffd581a59d
```

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "previousStatus": "ASSIGNED",
  "status": "IN_PROGRESS",
  "reason": "Starting endpoint investigation",
  "waitingForRequesterSince": null,
  "approvalReference": null,
  "transitionedAt": "2026-07-31T18:35:00Z",
  "version": 14
}
```

## 4. 错误形状

```json
{
  "type": "https://api.opsmind.example/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "code": "INVALID_STATUS_TRANSITION",
  "detail": "The requested ticket status transition is not allowed.",
  "instance": "/api/v1/tickets/{ticketId}/status-transitions",
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec"
}
```

## 5. HTTP 状态映射

| Condition | HTTP |
|---|---|
| validation or malformed header | `400` |
| missing/invalid authentication | `401` |
| actor or queue denied | `403` |
| ticket not found | `404` |
| invalid transition, missing assignee, idempotency conflict | `409` |
| stale version | `412` |
| missing `If-Match` | `428` |
| unsupported media type | `415` |

## 6. Validation

- `targetStatus` 必须是 `IN_PROGRESS`、`WAITING_FOR_USER` 或 `WAITING_FOR_APPROVAL`；
- `reason` trim 后长度为 3 到 500；
- `approvalReference` 仅在目标为 `WAITING_FOR_APPROVAL` 时允许且必填；
- `waitingForRequesterSince` 仅在目标为 `WAITING_FOR_USER` 时允许；
- 客户端不得传入 previous status、actor、assignee 或 version body 字段；
- `If-Match` 必须是单个 strong positive integer ETag；
- idempotency fingerprint 包含 actor、operation、ticket ID、规范化 body 和 expected version。
