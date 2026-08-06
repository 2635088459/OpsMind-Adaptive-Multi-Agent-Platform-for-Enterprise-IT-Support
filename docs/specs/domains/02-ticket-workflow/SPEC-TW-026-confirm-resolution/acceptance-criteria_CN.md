# SPEC-TW-026 验收标准

## 功能验收

- 给定 Ticket 处于允许状态，当提交 `confirmResolution` command 时，系统完成 `CLOSED` 目标效果。
- 事件 `ticket.resolution-confirmed.v1` 只在状态或 ownership mutation 成功提交后发布。
- response 返回 ticketId、state、workflowVersion、auditId 和 eventId。

## 幂等与并发

- 相同 idempotency key 重放返回第一次成功结果。
- 不同 command 并发修改同一 Ticket 时，只允许一个 version compare-and-swap 成功。
- stale expectedVersion 返回 `409 CONFLICT`。

## 安全与审计

- 未授权 actor 返回 `403 FORBIDDEN`。
- reason 缺失返回 `400 BAD_REQUEST`。
- 所有拒绝路径写入 command rejection metric，不发布成功事件。
