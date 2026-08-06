# SPEC-TW-022 — Start Verification（启动验证）

## 1. 目标

基于 Phase 06 保存的 tool result reference 启动独立 verification attempt。Ticket 必须处于 `VERIFYING`，且 verification attempt 必须绑定当前 Ticket、workflow、resolution cycle、tool result 和 attempt number。

## 2. 范围

包含：

- `POST /internal/v1/tickets/{ticketId}/verification/start`
- 创建 verification attempt；
- 保存 verificationId、toolResultId、attemptNumber；
- 发布 `ticket.verification-started.v1`；
- timeline、audit、outbox、idempotency。

不包含 Verification Agent 执行、success/failure result、resolution。

## 3. 核心规则

- Ticket 必须为 `VERIFYING`；
- tool result 必须属于当前 workflow/cycle/action；
- 不能为同一 tool result 创建两个 active verification attempt；
- attemptNumber 单调递增；
- 客户端不能伪造 verification result。
