# SPEC-ARO-040 — Confirm/Decline With Bounded Wait（确认/拒绝与限时同步等待）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`03-state-machine`, `08-transaction-and-outbox`, `10-failure-handling`
>
> 文档状态：Spec Planning

## 1. 目标

实现 `POST /api/v1/conversations/{id}/actions/{actionId}/confirm` 和 `.../decline`。新增一个 `AgentTaskState` 值 `AWAITING_USER_CONFIRMATION`，让任务暂停直到用户响应。低风险方案的 `confirm` 会真实派发工具请求并限时同步等待真实完成；高风险方案的 `confirm` 则改为创建一条真实的治理审批请求——响应永远如实说明发生的是哪一种，从不对审批分支假装立即完成。

## 2. 范围

包含：

- 新的 `AWAITING_USER_CONFIRMATION` 任务状态及其进入/退出迁移；
- 工具派发分支的限时同步等待执行路径（复用既有的 `tool.completed`/`tool.failed` 消费者，SPEC-ARO-020）；
- 高风险分支真实调用 `06-policy-approval-governance` 的发起审批端点；
- `decline` 的无操作（零副作用）路径。

不包含：

- 最初产出 `ProposedAction` 的消息轮次执行（SPEC-ARO-039）；
- 人工做出决定后，消费最终的 `approval.granted`/`approval.rejected` 事件——这已经是 SPEC-ARO-021 既有的工作，原样复用。

## 3. 核心规则

- `confirm`/`decline` 需要 `Idempotency-Key`；同一个 `actionId` 从不能被第二次确认或拒绝并产生新的真实副作用——重复请求返回当前真实的终态。
- 限时等待的超时时长是可配置的，不写死，真实默认值留给 phase 实施阶段通过压测确定——从不无限阻塞。
- 超时时如实返回 `"still-processing"`；从不伪造一个 `"done"` 结果。
- 高风险分支永远返回 `"awaiting-approval"`，压根不尝试限时等待——审批真实需要人来做决定，响应绝不能暗示别的可能。
- `decline` 触发零工具派发、零审批请求——通过完全不存在对应的 `tool_requests`/`approval_requests` 记录来验证。
