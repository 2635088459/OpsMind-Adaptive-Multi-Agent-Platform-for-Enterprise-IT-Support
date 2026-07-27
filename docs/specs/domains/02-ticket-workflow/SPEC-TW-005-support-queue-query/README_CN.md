# SPEC-TW-005 — Support Queue Query 文件说明

> **Spec ID：** SPEC-TW-005  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **功能：** IT Support 查询被授权的 Ticket Queue  
> **API：** `GET /api/v1/support/tickets`

---

# 1. 文件夹用途

本文件夹定义 Support Queue Query 的完整行为：

```text
Support Authentication
→ Queue Scope Authorization
→ Whitelisted Filters
→ Deterministic Urgency Sort
→ Signed Cursor Pagination
→ Minimal Support Projection
→ Audit / Observability
```

它回答：

- Support 能查看哪些 Ticket？
- Application、Team、Region、Tenant 权限如何下推到 SQL？
- 默认 Queue 包含哪些状态？
- SLA 和 Priority 如何决定排序？
- Cursor 如何绑定 Filter、Scope 和评估时间？
- 为什么 Queue Summary 不返回完整 Description 和 Message？
- Queue 查询怎样避免无界查询、Offset 和 N+1？

---

# 2. 文件结构

```text
SPEC-TW-005-support-queue-query/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── support-queue-response.schema.json
│   ├── support-ticket-summary.schema.json
│   ├── invalid-cursor-error.schema.json
│   ├── forbidden-filter-scope-error.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── default-queue-response.json
    ├── filtered-queue-response.json
    ├── empty-queue-response.json
    ├── invalid-cursor-error.json
    └── forbidden-filter-scope-error.json
```

---

# 3. Review 顺序

```text
README_CN
→ spec_CN
→ acceptance.feature
→ schemas
→ examples
→ traceability-entry
→ 英文一致性检查
```

---

# 4. 实现顺序

```text
Queue Authorization RED
→ Filter Validation RED
→ Sort Ranking RED
→ Cursor Integrity RED
→ PostgreSQL Projection RED
→ Query Plan RED
→ Controller GREEN
→ Audit / Telemetry
→ Verify
```

---

# 5. 关键边界

- 只有 Support 角色可以调用本 API。
- 需要 `tickets:read:queue`。
- Queue Scope 必须来自可信 Security Context，不能由客户端声明。
- Authorization Predicate 必须下推到 SQL。
- 默认只返回非终态 Ticket。
- 默认排序使用 SLA Urgency、Priority、CreatedAt、TicketId。
- Cursor 绑定 Filter、Sort、Actor Scope 和 `evaluationTime`。
- Queue 是实时 Operational View，不是历史快照导出。
- Response 不包含完整 Description、Message Content、Internal Note 或 Secret。
- 不使用 Offset、不返回 Total Count、不执行无界查询。
- Query 不修改 Ticket，也不创建 Outbox Event。

---

# 6. 代码位置

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

推荐类：

```text
QuerySupportQueueUseCase
QuerySupportQueueApplicationService
SupportQueueQuery
SupportQueueFilters
SupportQueueCursor
SupportQueueScope
SupportTicketSummary
JdbcSupportQueueQueryAdapter
SupportQueueCursorCodec
SupportTicketQueryController
```

---

# 7. 验证

```bash
./mvnw clean verify
```

完成前必须通过：

- Queue Authorization Test
- Filter Scope Test
- Cursor Integrity Test
- SLA Evaluation Time Test
- Stable Sort Test
- PostgreSQL Projection Test
- Query Plan Test
- Field Visibility Test
- Telemetry Redaction Test
- ArchUnit
- CI
