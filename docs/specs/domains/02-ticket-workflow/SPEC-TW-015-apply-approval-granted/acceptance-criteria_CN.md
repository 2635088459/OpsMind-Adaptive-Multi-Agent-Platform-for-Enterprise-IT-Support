# SPEC-TW-015 — 验收标准

- 匹配当前 open approval request 的 `approval.granted.v1` 成功应用。
- Ticket 必须为 `WAITING_FOR_APPROVAL`。
- `ticketId`、`workflowId`、`actionId`、`approvalId` 必须匹配。
- `approvedAt <= expiresAt`。
- duplicate event 幂等 ACK，不重复写业务效果。
- wrong producer/schema invalid 进入 DLQ。
- stale event ACK 并记录 stale，不推进 Ticket。
