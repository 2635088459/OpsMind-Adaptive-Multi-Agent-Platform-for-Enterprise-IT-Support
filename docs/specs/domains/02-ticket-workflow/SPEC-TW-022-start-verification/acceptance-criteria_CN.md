# SPEC-TW-022 — 验收标准

- `VERIFYING` Ticket 可启动 verification attempt。
- 非 `VERIFYING` 状态拒绝。
- tool result 不匹配当前 workflow/cycle/action 拒绝。
- duplicate idempotency replay 不创建第二个 attempt。
- 同一 tool result 已有 active attempt 返回 conflict。
- 成功写入 attempt、timeline、audit、outbox。
