# SPEC-TW-020 — 验收标准

- known-safe failed event 成功应用，Ticket 回到 `IN_PROGRESS`。
- pipeline/internal failure 可进入 `FAILED`。
- unsafe unknown side effect 被拒绝并要求 SPEC-TW-021 处理。
- 保存 `toolExecutionId`、failure code、failure class、failedAt。
- duplicate 幂等 ACK。
- wrong producer/schema invalid 进入 DLQ。
- stale event 不推进 Ticket。
