# SPEC-TW-023 — 验收标准

- 匹配当前 active attempt 的 success event 成功应用。
- attempt 标记为 `SUCCEEDED`。
- 保存 trusted verification evidence。
- duplicate 幂等 ACK。
- old workflow/cycle/attempt 记录 stale。
- conflicting failure result 进入 reconciliation。
- 不直接 close Ticket。
