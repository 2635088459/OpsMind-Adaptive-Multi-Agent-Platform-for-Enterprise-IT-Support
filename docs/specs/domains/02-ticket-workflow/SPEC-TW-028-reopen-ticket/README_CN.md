# SPEC-TW-028 — Reopen Ticket（重新打开工单）

## 1. 目标

重新打开 RESOLVED 或 policy 允许的 CLOSED Ticket，并创建新的 resolution cycle。

## 2. 范围

包含：

- `POST /v1/tickets/{ticketId}/reopen`；
- `RESOLVED or CLOSED -> REOPENED`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.reopened.v1`。

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
- reopen 必须保留上一轮 evidence，并创建新的 work cycle 后再回到 IN_PROGRESS。
