# SPEC-TW-013 — 持久化设计

真实 service migration 建议命名：

```text
services/ticket-workflow-service/src/main/resources/db/migration/V019__user_reply_and_resume.sql
```

Spec 参考文件：`V013__user_reply_and_resume.sql`。

## 更新

成功回复必须：

- 插入 `ticket_messages`；
- 更新 `ticket_user_input_requests.request_status = ANSWERED`；
- 设置 `answered_message_id` 和 `answered_at`；
- 更新 Ticket `status = IN_PROGRESS`；
- 清空 `waiting_for_requester_since`；
- version 加一；
- 写 status history、timeline、audit、outbox、idempotency。

更新必须包含 request status guard：

```sql
WHERE request_id = :request_id
  AND ticket_id = :ticket_id
  AND request_status = 'OPEN'
```
