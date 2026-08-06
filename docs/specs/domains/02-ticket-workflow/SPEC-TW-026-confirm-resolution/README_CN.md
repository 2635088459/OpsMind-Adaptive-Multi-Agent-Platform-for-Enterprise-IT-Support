# SPEC-TW-026 — Confirm Resolution（确认解决）

## 1. 目标

确认已解决工单被接受，并将 Ticket 从 RESOLVED 推进到 CLOSED。

## 2. 范围

包含：

- `POST /v1/tickets/{ticketId}/resolution-confirmation`；
- `RESOLVED -> CLOSED`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.resolution-confirmed.v1`。

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
- 确认必须引用当前 resolution cycle，不能关闭 stale 或已被 supersede 的 evidence。
