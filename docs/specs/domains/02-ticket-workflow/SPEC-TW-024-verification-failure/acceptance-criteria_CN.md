# SPEC-TW-024 — 验收标准

- retryable failure 回到 `IN_PROGRESS`。
- 第三次 failure 进入 `ESCALATED`。
- unsafe result 进入 `ESCALATED`。
- pipeline failure 可进入 `FAILED`。
- duplicate 幂等。
- old workflow/cycle/attempt stale。
- conflicting success 进入 reconciliation。
