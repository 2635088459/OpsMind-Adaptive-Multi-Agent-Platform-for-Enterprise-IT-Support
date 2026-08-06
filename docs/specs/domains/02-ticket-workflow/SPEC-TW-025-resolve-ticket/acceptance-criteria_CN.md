# SPEC-TW-025 — 验收标准

- trusted current verification evidence 可完成 resolve。
- 缺少 evidence 返回 `409 VERIFICATION_REQUIRED`。
- old workflow/cycle/attempt evidence 拒绝。
- resolution code/summary 校验与 Phase 03 保持一致。
- 成功完成 resolution cycle，Ticket 进入 `RESOLVED`。
- 发布 `ticket.resolved-with-verification.v1`。
- duplicate idempotency replay 不重复副作用。
