# SPEC-TW-014 — 验收标准

- `IN_PROGRESS` Ticket 可创建 approval request 并进入 `WAITING_FOR_APPROVAL`。
- 非 `IN_PROGRESS` 状态返回 `409 INVALID_STATUS_TRANSITION`。
- 已存在 open approval request 返回 `409 APPROVAL_REQUEST_ALREADY_OPEN`。
- pending action 缺少 ticket/workflow/action/risk context 返回 `400 VALIDATION_ERROR`。
- 成功写入 Ticket、approval request、history、timeline、audit、outbox、idempotency。
- 相同 idempotency key/payload replay 首次响应。
- 相同 key 不同 payload 返回 `409 IDEMPOTENCY_KEY_REUSED`。
