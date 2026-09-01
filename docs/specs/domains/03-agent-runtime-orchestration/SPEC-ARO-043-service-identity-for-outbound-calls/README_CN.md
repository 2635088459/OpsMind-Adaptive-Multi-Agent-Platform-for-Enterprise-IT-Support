# SPEC-ARO-043 — Service Identity for Outbound Calls（外呼服务身份）

> 领域：Agent Runtime Orchestration
>
> Phase：10 — 对话式接入
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`11-security`
>
> 文档状态：Spec Planning

## 1. 目标

给 `agent-runtime-service` 一个真实的 Keycloak client_credentials 服务身份，让它自己调用 `02-ticket-workflow`、`06-policy-approval-governance` 真实的、强制鉴权的端点（SPEC-ARO-038/040/041 需要）时携带一个真实有效的 JWT。这是那些 spec 的前置依赖，不是可选的基础设施。

## 2. 范围

包含：

- 一个新的、专用的 Keycloak 客户端注册（结构上与 2026-09-01 集成验证里搭的 `integration-test-client` 同类的 client_credentials 客户端，但这次是本服务真实拥有的、生产级的身份）；
- `agent-runtime-service` 内部的 token 获取、内存缓存、透明刷新；
- 只授予真实需要的 scope（`tickets:create`、`ticket:triage`，以及 `06-policy-approval-governance` 需要的部分）。

不包含：

- 除新增这一个客户端之外，对 `01-user-access-authentication` 自己 Keycloak realm 结构的任何改动；
- 任何面向人类的鉴权流程——这只是一个机器对机器的身份。

## 3. 核心规则

- 客户端密钥从不提交进源码——只通过环境变量注入，遵循本项目已经确立的密钥处理约定。
- token 获取一次，在本服务所有外呼之间复用/刷新——从不按每次请求重新鉴权。
- 拿不到 token 时，外呼干净地失败（明确、可见的错误）——从不悄悄以未鉴权或过期 token 的状态继续。
