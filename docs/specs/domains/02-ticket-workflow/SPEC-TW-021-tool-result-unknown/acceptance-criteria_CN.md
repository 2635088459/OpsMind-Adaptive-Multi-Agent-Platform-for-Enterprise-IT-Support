# SPEC-TW-021 — 验收标准

- 匹配当前 execution attempt 的 unknown event 成功记录。
- Ticket 进入 `ESCALATED` 或 reconciliation-required 状态。
- 保存 uncertainty reason、evidence references、observedAt。
- duplicate 幂等 ACK。
- late completed/failed event 被分类为 conflict/stale，不静默覆盖。
- wrong producer/schema invalid 进入 DLQ。
- 不自动重试原 ToolExecutionId。
