# SPEC-TW-017 — 验收标准

- 匹配当前 request 的 expired event 成功应用。
- `expiredAt >= expiresAt` 或本地时钟确认已过期。
- Ticket 回到 `IN_PROGRESS`。
- duplicate 幂等。
- granted 与 expired race 依据 committed terminal state 决定。
- stale/wrong producer/schema invalid 分类正确。
