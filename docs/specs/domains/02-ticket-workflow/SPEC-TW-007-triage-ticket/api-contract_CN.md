# SPEC-TW-007 — API 契约

## Endpoint

```http
POST /api/v1/tickets/{ticketId}/triage
```

这是专用生命周期命令。通用状态转换 API 不能执行分诊。

## 必需 Headers

| Header | 规则 |
|---|---|
| `Authorization` | Bearer Token 或获准的 Service Identity |
| `If-Match` | Ticket 强版本 ETag，例如 `"7"` |
| `Idempotency-Key` | UUID；对当前操作者和命令意图唯一 |
| `Content-Type` | `application/json` |
| `X-Correlation-Id` | 可选 UUID；缺少时由服务生成 |

## Path Parameter

`ticketId` 为 UUID。格式错误返回 `400 VALIDATION_ERROR`。

## 请求 Schema

| 字段 | 类型 | 必填 | 规则 |
|---|---|---:|---|
| `categoryId` | UUID | 是 | Active Category |
| `subcategoryId` | UUID/null | 否 | Active 且属于 `categoryId` |
| `priority` | enum | 是 | `LOW`、`MEDIUM`、`HIGH`、`CRITICAL` |
| `supportQueueId` | UUID | 是 | Active 且操作者有权限的队列 |
| `reason` | string | 是 | Trim 后 1～500 字符 |

拒绝未知属性。请求不能接受 `triagedBy`、`triagedAt`、`status`、`version` 或 Tenant Identity。

## 成功响应

```http
HTTP/1.1 200 OK
ETag: "8"
Content-Type: application/json
```

```json
{
  "ticketId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "status": "TRIAGED",
  "categoryId": "11111111-1111-1111-1111-111111111111",
  "subcategoryId": "22222222-2222-2222-2222-222222222222",
  "priority": "HIGH",
  "supportQueueId": "33333333-3333-3333-3333-333333333333",
  "triagedBy": "44444444-4444-4444-4444-444444444444",
  "triagedAt": "2026-07-29T18:30:00Z",
  "version": 8
}
```

幂等重放必须返回相同 Status、Body 和逻辑 ETag。

## 错误 Envelope

```json
{
  "type": "https://opsmind.dev/problems/version-conflict",
  "title": "Ticket version conflict",
  "status": 412,
  "code": "VERSION_CONFLICT",
  "detail": "The ticket was changed by another operation.",
  "instance": "/api/v1/tickets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/triage",
  "correlationId": "21ae628b-f15d-47d1-a937-1be0f85d4cd1",
  "errors": []
}
```

## 稳定错误

| HTTP | Code | 含义 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | UUID、JSON、Enum、长度或未知字段错误 |
| 401 | `AUTHENTICATION_REQUIRED` | 缺少身份或身份无效 |
| 403 | `TRIAGE_NOT_ALLOWED` | Actor Role 不能分诊 |
| 403 | `QUEUE_ACCESS_DENIED` | Actor 不能路由到目标队列 |
| 404 | `TICKET_NOT_FOUND` | Ticket 不存在或不属于可访问 Tenant |
| 409 | `INVALID_TICKET_STATE` | 当前状态不是 `OPEN` |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 相同 Key 对应不同标准化请求 |
| 412 | `VERSION_CONFLICT` | `If-Match` 不一致 |
| 422 | `TRIAGE_CATEGORY_INVALID` | Category 不存在或未启用 |
| 422 | `TRIAGE_SUBCATEGORY_INVALID` | Subcategory 不存在、未启用或不匹配 |
| 422 | `SUPPORT_QUEUE_INVALID` | Queue 不存在或未启用 |
| 428 | `PRECONDITION_REQUIRED` | 缺少 `If-Match` |
| 500 | `INTERNAL_ERROR` | 意外故障；不能部分提交 |

错误详情必须足够稳定以支持契约测试，且不能暴露 SQL、Stack Trace 或跨 Tenant 信息。

