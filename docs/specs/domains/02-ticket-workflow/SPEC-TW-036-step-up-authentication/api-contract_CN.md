# SPEC-TW-036 API Contract

## Internal Policy Endpoint

`POST /internal/v1/security/step-up/evaluate`

该 endpoint 表示本 SPEC 的 policy contract。真实业务 endpoint 可以内联调用同一 application policy，而不一定暴露此 internal endpoint。

## Request

```json
{
  "ticketId": "TCK-1001",
  "actorId": "user-123",
  "actorType": "IT_SUPPORT",
  "operation": "ticket.command",
  "context": {
    "supportQueueId": "00000000-0000-0000-0000-000000000001"
  }
}
```

## Response 200

```json
{
  "decision": "ALLOW",
  "decisionCode": "POLICY_ALLOWED",
  "auditRequired": true
}
```

## Errors

- `400 BAD_REQUEST`：policy input 无效；
- `403 FORBIDDEN`：policy 拒绝；
- `409 CONFLICT`：当前 Ticket 状态或上下文不允许；
- `500 INTERNAL_ERROR`：required audit/secret/step-up guard fail-closed。
