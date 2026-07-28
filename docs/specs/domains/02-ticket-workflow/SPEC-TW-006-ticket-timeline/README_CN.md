# SPEC-TW-006 — Ticket Timeline 文件说明

> **Spec ID：** SPEC-TW-006  
> **阶段：** Phase 02 — Ticket Query and Message Slice  
> **功能：** 按权限查看 Ticket 的统一时间线  
> **API：** `GET /api/v1/tickets/{ticketId}/timeline`

---

# 1. 文件夹用途

本文件夹定义 Ticket Timeline Query 的完整行为：

```text
Authentication
→ Resource Authorization
→ Actor-specific Visibility
→ Snapshot Boundary
→ Unified Timeline Projection
→ Stable Keyset Pagination
→ Sensitive-read Audit
→ Safe Response
```

Phase 02 的 Timeline 组合：

```text
Ticket Created
Status History
Public Requester Messages
Public Support Messages
Internal Support Notes
```

它不直接暴露原始 Audit Record，也不读取 Approval、Tool 或 Verification 的完整内部数据。

---

# 2. 文件结构

```text
SPEC-TW-006-ticket-timeline/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── employee-timeline-response.schema.json
│   ├── support-timeline-response.schema.json
│   ├── employee-timeline-item.schema.json
│   ├── support-timeline-item.schema.json
│   ├── invalid-cursor-error.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── employee-timeline-first-page.json
    ├── employee-timeline-next-page.json
    ├── support-timeline-with-internal-note.json
    ├── empty-timeline-response.json
    └── invalid-cursor-error.json
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
Resource Authorization RED
→ Employee Visibility RED
→ Support Internal Visibility RED
→ Timeline Mapping RED
→ Snapshot/Cursor RED
→ PostgreSQL UNION Projection RED
→ Audit Fail-closed RED
→ Controller GREEN
→ Telemetry Redaction
→ Verify
```

---

# 5. 关键边界

- Employee 只能查看自己 Ticket 的 Public Timeline。
- Support 只能查看授权 Ticket，并需要额外 Scope 才能看到 Internal Note。
- View 由服务端 Principal 决定，客户端不能请求更高权限 View。
- Timeline 使用固定 `snapshotAt`，同一 Cursor 会话不会混入新项目。
- 默认排序为 `occurredAt ASC, itemTypeRank ASC, itemId ASC`。
- Cursor 绑定 Ticket、Actor、View、Scope、Snapshot 和 Sort Version。
- Employee Timeline 不返回 Author ID、Internal Reason 或 Internal Note。
- Support Sensitive Timeline Read 根据 Policy 写入 Audit。
- Query 不修改 Ticket，也不创建 Outbox Event。
- Phase 02 不直接把原始 Audit Record 暴露为 Timeline Item。

---

# 6. 代码位置

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

推荐类：

```text
GetTicketTimelineUseCase
GetTicketTimelineApplicationService
TicketTimelineQuery
TicketTimelineCursor
TicketTimelineViewPolicy
TicketTimelineItem
JdbcTicketTimelineQueryAdapter
TicketTimelineCursorCodec
TicketTimelineController
```

---

# 7. 验证

```bash
./mvnw clean verify
```

完成前必须通过：

- Ownership / Support Scope Test
- Public / Internal Visibility Test
- Snapshot Pagination Test
- Stable Ordering Test
- PostgreSQL Projection Test
- Required Audit Fail-closed Test
- Response / Telemetry Redaction Test
- Non-mutation Test
- ArchUnit
- CI
