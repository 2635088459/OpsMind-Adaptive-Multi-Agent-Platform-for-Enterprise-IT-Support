# SPEC-TW-004 — Add Ticket Message 文件说明

> **Spec ID：** SPEC-TW-004  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **API：** `POST /api/v1/tickets/{ticketId}/messages`

## 1. 用途

本文件夹定义 Employee 和 IT Support 向 Ticket 追加消息时的完整规则：

```text
权限
→ Message Type
→ Visibility
→ Ticket State Guard
→ Idempotency
→ Append-only Persistence
→ Audit
→ Outbox
→ Tests
```

## 2. 文件结构

```text
SPEC-TW-004-add-ticket-message/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── employee-add-message-request.schema.json
│   ├── support-add-message-request.schema.json
│   ├── add-message-response.schema.json
│   ├── ticket-message-added-v1.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── employee-public-message-request.json
    ├── support-public-message-request.json
    ├── support-internal-note-request.json
    ├── add-message-response.json
    ├── ticket-message-added-v1.json
    ├── invalid-message-error.json
    ├── message-not-allowed-error.json
    └── idempotency-key-reused-error.json
```

## 3. Review 顺序

```text
README_CN
→ spec_CN
→ acceptance.feature
→ schemas
→ examples
→ traceability-entry
→ 英文一致性检查
```

## 4. 实现顺序

```text
Message Domain RED
→ Authorization RED
→ State Guard RED
→ Idempotency RED
→ Persistence/Atomicity RED
→ API RED
→ Event Contract RED
→ Minimum Code
→ Redaction
→ Verify
```

## 5. 关键边界

- Employee 只能创建 `PUBLIC_REQUESTER_MESSAGE`。
- Support 可以创建 `PUBLIC_SUPPORT_MESSAGE` 或 `INTERNAL_SUPPORT_NOTE`。
- Author 和 Visibility 必须由服务端决定。
- Message Append-only，不支持 Update/Delete。
- `WAITING_FOR_USER` 回复不在 Phase 02 自动切换状态。
- `RESOLVED` 留言不自动 Reopen。
- `CLOSED` 和 `CANCELLED` 拒绝新消息。
- Message、Audit、Outbox、Idempotency 原子提交。
- Event、Audit、Log、Trace 不保存完整 Content。

## 6. 代码位置

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

## 7. 验证

```bash
./mvnw clean verify
```
