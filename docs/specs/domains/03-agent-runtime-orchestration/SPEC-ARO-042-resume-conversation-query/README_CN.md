# SPEC-ARO-042 — Resume Conversation Query（恢复会话查询）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`04-use-cases`, `05-api-contracts`, `11-security`
>
> 文档状态：Spec Planning

## 1. 目标

实现 `GET /api/v1/conversations/{conversationId}`，把已经真实存在的 `GET /{workflow_instance_id}` 查询映射成对话形状的读模型，再加一个新查询——"我最近一次活跃/已转人工的会话"，支撑 09 号 domain 的 UC-EP-06（一个不知道自己 `conversationId` 的返回员工）。

## 2. 范围

包含：

- 在既有 `WorkflowQueryPort` 之上的对话形状读适配器；
- 新查询能力：按请求方身份查找归属于该员工的最近活跃/已转人工的会话。

不包含：

- 任何写路径（本 spec 完全只读）；
- 完整的多会话历史列表（09 号 domain 自己的 roadmap 里已经明确列为 non-goal）。

## 3. 核心规则

- 本 spec 不引入任何新的写路径——纯只读。
- `conversationId` 继续等于 `workflowInstanceId`（SPEC-ARO-037）；查询时不引入平行身份体系。
- 属于其他员工的会话从不被返回——鉴权方式与本平台其他地方已有的 `01-user-access-authentication` 资源归属校验一致。
