# SPEC-TW-008 — API 契约

## 1. 通用 Headers

```http
Authorization: Bearer <token>
Content-Type: application/json
If-Match: "<positive-version>"
Idempotency-Key: <8-to-128-character-key>
X-Correlation-ID: <optional-UUID>
```

服务端从认证上下文获得 `tenantId` 和 `actorId`，客户端不得在 Body 中传入。

## 2. Assign

```http
POST /api/v1/tickets/{ticketId}/assign
```

```json
{
  "assigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "reason": "Primary endpoint support owner"
}
```

Ticket 必须处于 `TRIAGED` 且当前无负责人。

## 3. Reassign

```http
POST /api/v1/tickets/{ticketId}/reassign
```

```json
{
  "assigneeId": "98bf86d3-d709-448b-acd9-ef9ecbbc3d23",
  "reason": "Escalated to network specialist"
}
```

新负责人必须不同于当前负责人，Ticket 状态保持不变。

## 4. Unassign

```http
POST /api/v1/tickets/{ticketId}/unassign
```

```json
{
  "reason": "Agent left the support rotation"
}
```

Ticket 状态必须为 `ASSIGNED`。

## 5. 成功响应

```http
HTTP/1.1 200 OK
ETag: "13"
Location: /api/v1/tickets/6c2ad02e-c394-41fb-8e38-dfffd581a59d
```

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "status": "ASSIGNED",
  "assignee": {
    "id": "17cb78fb-c36d-4bb2-9687-84d86d726192",
    "displayName": "Sam Lee"
  },
  "assignedAt": "2026-07-29T19:15:00Z",
  "version": 13
}
```

Unassign 成功时，`assignee` 与 `assignedAt` 为 `null`。

## 6. 错误结构

```json
{
  "type": "https://api.opsmind.example/problems/version-conflict",
  "title": "Version conflict",
  "status": 409,
  "code": "VERSION_CONFLICT",
  "detail": "The ticket was modified by another command.",
  "instance": "/api/v1/tickets/{ticketId}/assign",
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec"
}
```

## 7. HTTP 状态映射

| 条件 | HTTP |
|---|---|
| 参数或 Header 格式错误 | `400` |
| 缺少认证或认证无效 | `401` |
| 角色或队列权限不足 | `403` |
| Ticket 或 Assignee 不存在 | `404` |
| 状态、版本、资格或幂等冲突 | `409` |
| 不支持的媒体类型 | `415` |

## 8. 校验

- 所有 ID 必须为 UUID；
- `reason` 去除首尾空格后必填，长度为 3–500；
- Assign/Reassign 必须提供 `assigneeId`，Unassign 禁止提供；
- `If-Match` 必须包含且仅包含一个强 ETag 正整数；
- 幂等指纹包括 Tenant、Actor、Operation、Ticket ID、规范化 Body 和 Expected Version。
