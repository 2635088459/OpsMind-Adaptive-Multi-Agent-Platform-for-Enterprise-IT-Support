# SPEC-TW-027 — Auto Close（自动关闭）

## 1. 目标

在 policy window 到期且没有 rejection/reopen 的情况下自动关闭已解决 Ticket。

## 2. 范围

包含：

- `POST /internal/v1/tickets/{ticketId}/auto-close`；
- `RESOLVED -> CLOSED`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.auto-closed.v1`。

不包含：

- Phase 06 tool execution；
- Phase 07 verification evidence 生成；
- 跨 domain 数据修复。

## 3. 核心规则

- command 必须绑定当前 Ticket version，避免 stale write；
- actor 必须具备当前动作权限；
- reason 必填，并写入 timeline/audit；
- duplicate idempotency key 必须返回同一结果；
- terminal state command 必须被拒绝；
- scheduler 信号只是提示，service 必须在锁内重新计算 eligibility。
