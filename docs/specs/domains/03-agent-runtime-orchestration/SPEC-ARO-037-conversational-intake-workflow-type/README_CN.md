# SPEC-ARO-037 — Conversational Intake Workflow Type（对话式接入工作流类型）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`01-domain-model`, `03-state-machine`
>
> 文档状态：Spec Planning

## 1. 目标

新增 `conversational_intake` 这个 `workflow_type`，以及配套的两个 `AgentTask` `task_type` 值（`process_user_message`、`execute_confirmed_action`），加上该工作流类型固定的、内部拥有的 `task_graph` 模板——不改变任何现有 `WorkflowInstance`/`AgentTask` 字段或状态迁移的语义。

## 2. 范围

包含：

- 新增的 `workflow_type`/`task_type` 枚举值及其允许值迁移；
- `conversational_intake` 内部解析的固定 `task_graph` 模板（与现有通用 `start_workflow` 契约不同，调用方从不提供）；
- 明确 09/10 号 domain 消费的公开 `conversationId` 就是这个工作流类型的 `workflowInstanceId`——不是另一套 ID 体系。

不包含：

- 一次消息轮次（SPEC-ARO-039）或确认/拒绝动作（SPEC-ARO-040）的真正执行逻辑；
- 建单（SPEC-ARO-038）或基于分诊的转人工（SPEC-ARO-041）；
- 任何现有 `workflow_type` 行为的变更。

## 3. 核心规则

- 只新增枚举值，不改变任何现有枚举值的含义或迁移规则；
- `conversational_intake` 的任务图是固定、内部的——调用 `POST /api/v1/conversations` 的一方从不提供，与既有通用 `start_workflow` 端点不同；
- `conversationId` 从不新造一套身份体系——就是 `workflowInstanceId` 本身，原样复用。
