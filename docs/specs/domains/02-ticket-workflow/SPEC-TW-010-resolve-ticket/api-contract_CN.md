# SPEC-TW-010 — API 契约

## 1. 通用 Headers

```http
Authorization: Bearer <token>
Content-Type: application/json
If-Match: "<positive-version>"
Idempotency-Key: <8-to-128-character-key>
X-Correlation-ID: <optional-UUID>
```

服务端从认证上下文推导 actor identity 与授权队列。客户端不得在 body 中传入 actor、tenant、queue scope、resolvedBy 或 resolvedAt。

## 2. Resolve Ticket

```http
POST /api/v1/tickets/{ticketId}/resolution
```

```json
{
  "resolutionCode": "FIXED",
  "resolutionSummary": "Reinstalled the endpoint management profile and confirmed the device checked in successfully."
}
```

要求 Ticket 当前为 `IN_PROGRESS` 且已有负责人。

## 3. 成功响应

```http
HTTP/1.1 200 OK
ETag: "18"
Location: /api/v1/tickets/6c2ad02e-c394-41fb-8e38-dfffd581a59d
```

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "previousStatus": "IN_PROGRESS",
  "status": "RESOLVED",
  "resolutionCode": "FIXED",
  "resolutionSummary": "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
  "resolvedBy": "sam.support",
  "resolvedAt": "2026-07-31T19:05:00Z",
  "version": 18
}
```

## 4. 错误形状

```json
{
  "type": "https://api.opsmind.example/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "code": "INVALID_STATUS_TRANSITION",
  "detail": "Only an IN_PROGRESS ticket can be resolved.",
  "instance": "/api/v1/tickets/{ticketId}/resolution",
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
| invalid transition, missing assignee, cycle conflict, idempotency conflict | `409` |
| unsupported resolution code | `422` |
| stale version | `412` |
| missing `If-Match` | `428` |
| unsupported media type | `415` |

## 6. Validation

- `resolutionCode` 必须是受控枚举；
- `resolutionSummary` trim 后长度为 10 到 5000；
- 客户端不得传入 previous status、status、actor、assignee、resolvedAt 或 version body 字段；
- `If-Match` 必须是单个 strong positive integer ETag；
- idempotency fingerprint 包含 actor、operation、ticket ID、规范化 body 和 expected version。
