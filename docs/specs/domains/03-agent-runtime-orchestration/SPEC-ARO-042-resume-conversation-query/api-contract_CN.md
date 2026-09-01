# SPEC-ARO-042 — API Contract

目标：支撑 `恢复会话查询`。

- `GET /api/v1/conversations/{conversationId}` → 映射到既有的 `WorkflowQueryPort.find(workflowInstanceId)`，响应重塑为对话视图。
- `GET /api/v1/conversations:mostRecent`（或实施时确定的等效"按身份查询"形状）→ 从 JWT 解析出调用员工的身份，返回其最近一次活跃/已转人工的会话，不存在时返回 `404`/空结果。
- 标出一个可能需要新增列的地方：如果 `workflow_instances` 目前没有可查询的"创建者身份"字段，可能需要新增——留给实施时对着真实 schema 确认。
