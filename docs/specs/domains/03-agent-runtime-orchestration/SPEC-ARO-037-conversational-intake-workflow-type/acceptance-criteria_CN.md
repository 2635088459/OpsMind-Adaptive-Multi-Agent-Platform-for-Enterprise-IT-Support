# SPEC-ARO-037 — Acceptance Criteria

目标：支撑 `对话式接入工作流类型`。

- 能用 `workflow_type="conversational_intake"` 创建一个 `WorkflowInstance`，并通过既有持久化校验。
- 任何既有 `workflow_type` 的行为、状态迁移、测试都不发生变化。
- 该工作流类型的固定 `task_graph` 模板能确定性地解析出来，不需要调用方提供任务图。
- 迁移后 `agent-runtime-service` 的完整既有测试套件依然全绿。
