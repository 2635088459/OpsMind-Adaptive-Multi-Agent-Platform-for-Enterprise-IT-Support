# SPEC-TW-029 — Cancel Ticket（取消工单）

## 1. 目标

取消尚未终结的 Ticket，并阻止后续生命周期推进。

## 2. 范围

包含：

- `POST /v1/tickets/{ticketId}/cancel`；
- `non-terminal mutable states -> CANCELLED`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.cancelled.v1`。

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
- cancel 是 terminal state，后续 close、reopen、assign、escalate、resume 都必须拒绝。
