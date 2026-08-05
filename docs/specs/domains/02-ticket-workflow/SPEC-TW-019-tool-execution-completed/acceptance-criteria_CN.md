# SPEC-TW-019 — 验收标准

- 匹配当前 execution attempt 的 completed event 成功应用。
- Ticket 从 `EXECUTING` 进入 `VERIFYING`。
- 保存 `toolExecutionId`、`toolResultId`、`completedAt` 和 result summary。
- 写 status history、timeline、audit、outbox。
- duplicate event 幂等 ACK。
- wrong producer/schema invalid 进入 DLQ。
- stale workflow/action/authorization 不推进 Ticket。
- Tool success 不直接 resolve。
