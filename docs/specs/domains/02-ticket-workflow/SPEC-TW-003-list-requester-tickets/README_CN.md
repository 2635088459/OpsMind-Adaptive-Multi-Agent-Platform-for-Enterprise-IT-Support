# SPEC-TW-003 — List Requester Tickets 文件说明

> **Spec ID：** SPEC-TW-003  
> **所属阶段：** Phase 02 — Ticket Query and Message Slice  
> **API：** `GET /api/v1/tickets`

## 1. 作用

本文件夹定义当前 Employee 查看自己创建的 Ticket 列表时的完整规格，包括所有权、Filter、稳定排序、Cursor Pagination、Cursor 防篡改、Response Schema、测试和 Traceability。

## 2. 文件结构

```text
SPEC-TW-003-list-requester-tickets/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── requester-ticket-list-response.schema.json
│   ├── ticket-summary.schema.json
│   ├── invalid-cursor-error.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── first-page-response.json
    ├── next-page-response.json
    ├── empty-list-response.json
    ├── filtered-list-response.json
    └── invalid-cursor-error.json
```

## 3. Review 顺序

```text
README_CN
→ spec_CN
→ acceptance.feature
→ schemas
→ examples
→ traceability-entry
→ 英文版一致性检查
```

## 4. 实现顺序

```text
Ownership Test
→ Filter Validation Test
→ Cursor Codec Test
→ Stable Sort Test
→ PostgreSQL Projection
→ Controller
→ Telemetry
→ Traceability
```

## 5. 关键边界

- SQL 必须包含 `requester_id = principal.subject`。
- 默认排序为 `createdAt DESC, ticketId DESC`。
- 使用 Keyset Pagination，不使用 Offset。
- Response 不包含完整 Description、RequesterId 或 Internal 字段。
- Query 不修改 Ticket，也不产生 Outbox Event。

## 6. 代码位置

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

推荐类：

```text
ListRequesterTicketsUseCase
ListRequesterTicketsApplicationService
RequesterTicketQueryPort
JdbcRequesterTicketQueryAdapter
TicketListCursorCodec
PublicTicketQueryController
```

## 7. 验证

```bash
./mvnw clean verify
```

完成前必须通过 Cursor、Ownership、Pagination、Schema、PostgreSQL、ArchUnit 和 CI 测试。
