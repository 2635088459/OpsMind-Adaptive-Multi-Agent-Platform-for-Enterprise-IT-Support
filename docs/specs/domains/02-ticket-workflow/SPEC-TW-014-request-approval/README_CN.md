# SPEC-TW-014 — Request Approval（请求审批）

## 1. 目标

为一个高风险 pending action 请求审批，并将 Ticket 从 `IN_PROGRESS` 推进到 `WAITING_FOR_APPROVAL`。

成功命令必须保存 pending action、approval reference、risk context、requestedBy/requestedAt，并写入 timeline、audit、status history、outbox 和 idempotency response。

## 2. 范围

包含：

- `POST /api/v1/tickets/{ticketId}/approval-requests`
- `IN_PROGRESS -> WAITING_FOR_APPROVAL`
- pending action reference
- approval reference
- risk context snapshot
- `ticket.approval-wait-started.v1`

不包含真实 Approval Service、审批 UI、Tool execution。

## 3. 核心规则

- Ticket 必须为 `IN_PROGRESS`；
- Ticket 必须有负责人和 support queue；
- pending action 必须有稳定 `actionId`、`actionType`、`workflowId`；
- 同一 Ticket 同时只能有一个 open approval request；
- approval 绑定 Ticket、workflow、action、risk context；
- 客户端不能伪造 approver 或 approval decision。

## 4. 文件索引

本目录包含中英文设计、OpenAPI、AsyncAPI、HTTP 示例和参考 migration。
