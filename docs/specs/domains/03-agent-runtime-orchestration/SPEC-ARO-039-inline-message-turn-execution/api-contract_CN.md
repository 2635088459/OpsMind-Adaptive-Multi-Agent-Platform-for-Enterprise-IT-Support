# SPEC-ARO-039 — API Contract

目标：支撑 `消息轮次内联执行`。

- `POST /api/v1/conversations/{conversationId}/messages`，需要 `Idempotency-Key`。
- 请求：`{text, attachmentRefs[]}`（与 09 号 domain `05-api-contracts` §2.2 完全一致）。
- 响应：判别联合类型，恰好是 `{type: "text", text}` / `{type: "proposedAction", actionId, summary, riskLevel, requiresConfirmation}` / `{type: "escalation", ticketId, displayId, reason, assignedTeam}` 三者之一。
- 附件引用（`attachmentRefs`）对着独立立项的共享附件能力解析（见 `09-employee-portal` 自己 `05-api-contracts` §3）——本 spec 只消费已经上传完成、`ready` 状态的引用。
