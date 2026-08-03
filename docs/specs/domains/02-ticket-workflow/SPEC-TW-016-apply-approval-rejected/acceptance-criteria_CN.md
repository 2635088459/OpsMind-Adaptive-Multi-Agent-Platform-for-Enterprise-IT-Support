# SPEC-TW-016 — 验收标准

- 匹配当前 request 的 rejected event 成功应用。
- Ticket 从 `WAITING_FOR_APPROVAL` 回到 `IN_PROGRESS`。
- request 状态为 `REJECTED`，保存 `rejectedBy`、`rejectedAt`、`rejectionReason`。
- duplicate 幂等。
- wrong producer/schema invalid 进 DLQ。
- stale event 不推进 Ticket。
