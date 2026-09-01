# SPEC-ARO-041 — Escalation Via Existing Triage（借助既有分诊转人工）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`04-use-cases`, `05-api-contracts`
>
> 文档状态：Spec Planning

## 1. 目标

当 `conversational_intake` 工作流判定自己无法/不适合继续自动处理时，调用 `02-ticket-workflow` 已经真实存在的 `POST /{ticketId}/triage` 端点，把工单路由到真实的支持队列——从不创建第二张工单——然后正常结束这个工作流实例。

## 2. 范围

包含：

- 内部调用真实的、已建成的分诊端点，携带代表这个自动化 agent 的行为者身份；
- 转人工成功后，该工作流实例自身的终态迁移。

不包含：

- 对 `02-ticket-workflow` 自己分诊端点、其鉴权模型或状态机的任何改动；
- 本 spec 转人工之后对同一张工单的任何进一步自动化尝试——从这一刻起完全是 `10-support-console` 的地盘。

## 3. 核心规则

- 转人工从不创建第二张工单。工单在 SPEC-ARO-038 时就已经存在——本 spec 只是给它分诊。
- 分诊调用的行为者是一个真实、可区分的自动化 agent 身份（例如 `actor_type=AUTOMATION_AGENT`），从不伪装成人类支持坐席。
- 转人工成功后，该工作流实例进入终态，从不被恢复来对同一张工单继续尝试自助处理。
