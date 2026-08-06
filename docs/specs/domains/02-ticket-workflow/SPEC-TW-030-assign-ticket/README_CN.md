# SPEC-TW-030 — Assign Ticket（转派工单）

## 1. 目标

更新 Ticket owner、queue、team 或 assignee，但不隐式改变解决进度。

## 2. 范围

包含：

- `POST /v1/tickets/{ticketId}/assignment`；
- `mutable non-terminal states -> same lifecycle state`；
- actor、reason、idempotency key、workflow version 和 audit trail；
- `ticket.assigned.v1`。

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
- assignment 是 ownership mutation，必须有独立 audit version，不能改写 resolution evidence。
