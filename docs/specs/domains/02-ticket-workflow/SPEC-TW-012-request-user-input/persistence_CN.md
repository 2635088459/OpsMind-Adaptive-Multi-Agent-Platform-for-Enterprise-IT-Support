# SPEC-TW-012 — 持久化设计

真实 service migration 建议命名：

```text
services/ticket-workflow-service/src/main/resources/db/migration/V018__request_user_input.sql
```

Spec 参考文件：`V012__request_user_input.sql`。

## 表：ticket_user_input_requests

字段：

- `request_id UUID PRIMARY KEY`
- `ticket_id UUID NOT NULL`
- `request_status VARCHAR(24) NOT NULL`
- `prompt TEXT NOT NULL`
- `requested_fields JSONB`
- `requested_by_type VARCHAR(32) NOT NULL`
- `requested_by_id VARCHAR(128) NOT NULL`
- `requested_at TIMESTAMPTZ NOT NULL`
- `resume_status VARCHAR(32) NOT NULL`
- `answered_message_id UUID`
- `answered_at TIMESTAMPTZ`
- `expires_at TIMESTAMPTZ`
- `correlation_id VARCHAR(128)`

唯一约束：

```sql
CREATE UNIQUE INDEX uq_ticket_one_open_user_input_request
    ON ticket.ticket_user_input_requests (ticket_id)
    WHERE request_status = 'OPEN';
```

Ticket 更新必须带 `status = 'IN_PROGRESS'` 和 expected version guard。
