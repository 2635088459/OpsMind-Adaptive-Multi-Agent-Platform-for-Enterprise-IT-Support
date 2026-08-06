# SPEC-TW-031 — Escalate Ticket（升级工单）

## 1. 目标

将 Ticket 升级到更高支持通道，并保留升级原因与当前工作上下文。

## 2. 范围

包含：

- `POST /v1/tickets/{ticketId}/escalation`；
- `mutable non-terminal states -> ESCALATED`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.escalated.v1`。

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
- escalation 会冻结自动推进，直到明确 resume 或 cancel。
