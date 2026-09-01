# SPEC-ARO-038 — Start Conversation Creates Ticket（发起会话即建单）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`04-use-cases`, `05-api-contracts`, `08-transaction-and-outbox`
>
> 文档状态：Spec Planning

## 1. 目标

实现 `POST /api/v1/conversations`：真实在 `02-ticket-workflow` 建一张工单，再创建一个绑定这张真实工单的 `conversational_intake` `WorkflowInstance`（SPEC-ARO-037）——这是每一次对话都要经过的入口。

## 2. 范围

包含：

- 新的公开 REST 端点及其请求/响应形状；
- 真实调用 `02-ticket-workflow` 自己的 `POST /api/v1/tickets`；
- 直接通过内部命令创建 `WorkflowInstance`（不经过 `ticket-created` 事件摄取端点），因为 `ticketId` 在同一次请求内已经同步拿到了。

不包含：

- 消息轮次执行（SPEC-ARO-039）或确认/拒绝（SPEC-ARO-040）；
- 用于给这次外呼鉴权的服务身份机制本身（SPEC-ARO-043，是本 spec 的依赖）。

## 3. 核心规则

- 建的工单永远是真实的，经由 `02-ticket-workflow` 自己的端点——从不在本地伪造或模拟。
- 没有先真实、成功建单，就不会创建 `WorkflowInstance`。
- 请求需要 `Idempotency-Key`，遵循平台既有约定。
- 如果建单成功但随后创建 workflow instance 失败，工单保持真实、正常的状态（`NEW`），依然能被 `02-ticket-workflow` 自己的常规查询看到——从不被静默隐藏或孤立。
