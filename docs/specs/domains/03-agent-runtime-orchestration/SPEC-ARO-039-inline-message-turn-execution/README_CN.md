# SPEC-ARO-039 — Inline Message Turn Execution（消息轮次内联执行）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`03-state-machine`, `04-use-cases`, `05-api-contracts`, `09-concurrency-and-idempotency`
>
> 文档状态：Spec Planning

## 1. 目标

实现 `POST /api/v1/conversations/{conversationId}/messages`：同步内联执行一个 `process_user_message` `AgentTask`（绕开既有的异步 claim/complete worker 队列），返回恰好三选一的响应：纯文本、方案提议、或转人工通知。

## 2. 范围

包含：

- 针对 `task_type="process_user_message"` 的新内联执行器，区别于既有异步 worker 路径；
- 在查询 `04-memory-knowledge` 之前写 checkpoint；
- 文本/方案提议/转人工三选一的响应判别。

不包含：

- 确认/拒绝一个方案提议（SPEC-ARO-040）；
- 真正通过分诊转人工（SPEC-ARO-041）——本 spec 只产出 `escalation` 这个响应形状，真正的分诊调用属于 SPEC-ARO-040/041 相邻的确认流程。

## 3. 核心规则

- 内联执行器对这个 `task_type` 从不使用既有的异步 `claim`/`complete` worker 端点——完全在处理这条消息的 HTTP 请求内运行。
- 在任何外呼（知识检索，之后可能的工具派发或审批请求）之前先写 checkpoint，遵循既有的"每个外部副作用之前必须先有 checkpoint"不变量。
- 响应永远恰好是三种已声明形状之一——从不是残缺或模糊的形状，也从不在 agent 实际想提方案/转人工时悄悄退化成纯文本。
- 同一条消息重复提交同一个 `Idempotency-Key`，从不重新跑一遍底层 LLM/知识检索调用——返回原始结果。
