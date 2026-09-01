# SPEC-ARO-038 — Acceptance Criteria

目标：支撑 `发起会话即建单`。

- 调用本端点后，`02-ticket-workflow` 自己的数据库里出现一条真实工单记录。
- `agent-runtime-service` 自己的 schema 里出现一条真实 `workflow_instances` 记录，引用那个真实 `ticketId`。
- 重复提交同一个 `Idempotency-Key` 从不产生第二张工单或第二个 workflow instance。
- 如果 `02-ticket-workflow` 不可用，本端点干净地失败并给出明确错误——从不伪造一个假的 `conversationId`。
