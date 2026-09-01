# SPEC-ARO-037 — 领域规则

目标：支撑 `对话式接入工作流类型`。

- `workflow_type="conversational_intake"` 原样复用 `WorkflowInstance` 现有的 version/state 不变量。
- `task_type="process_user_message"`/`"execute_confirmed_action"` 遵循与所有既有 `task_type` 完全相同的 claim/version 规则。
- 本 spec 本身不引入任何新的 `WorkflowState`/`AgentTaskState` 值（新的 `AWAITING_USER_CONFIRMATION` 任务状态属于 SPEC-ARO-040）。
