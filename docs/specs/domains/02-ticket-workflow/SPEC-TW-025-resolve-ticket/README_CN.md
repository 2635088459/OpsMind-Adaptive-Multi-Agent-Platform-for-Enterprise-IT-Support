# SPEC-TW-025 — Resolve Ticket with Verification（基于验证解决工单）

## 1. 目标

基于 `SPEC-TW-023` 保存的 trusted verification evidence 完成 resolution，将 Ticket 从 `VERIFYING` 推进到 `RESOLVED`，并完整保存 resolution cycle。

这不是 Phase 03 `SPEC-TW-010` 的重复实现；本 SPEC 要求 verification evidence，是自动化/工具执行路径的 resolution。

## 2. 范围

包含：

- `POST /internal/v1/tickets/{ticketId}/verified-resolution`
- `VERIFYING -> RESOLVED`
- resolution code/summary/by/at；
- verification evidence reference；
- resolution cycle completion；
- `ticket.resolved-with-verification.v1`。

不包含 close、auto-close、reopen。

## 3. 核心规则

- Ticket 必须为 `VERIFYING`；
- verification evidence 必须 trusted、current、successful；
- evidence 必须匹配当前 workflow/cycle/attempt；
- resolution summary/code 仍然必填；
- `RESOLVED` 不等于 `CLOSED`。
