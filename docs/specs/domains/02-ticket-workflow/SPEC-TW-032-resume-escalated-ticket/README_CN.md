# SPEC-TW-032 — Resume Escalated Ticket（恢复升级工单）

## 1. 目标

将 ESCALATED Ticket 恢复到 active work，并保留 escalation audit history。

## 2. 范围

包含：

- `POST /v1/tickets/{ticketId}/escalation/resume`；
- `ESCALATED -> IN_PROGRESS`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.escalation-resumed.v1`。

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
- resume 必须选择下一 owner/queue，不能丢弃 escalation resolution notes。
