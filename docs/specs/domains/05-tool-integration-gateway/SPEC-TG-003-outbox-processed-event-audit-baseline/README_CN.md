# SPEC-TG-003 — Outbox、Processed Event 与审计 baseline

> 领域：Tool Integration Gateway
>
> Phase：00 — 工程基础
>
> 服务：`tool-integration-gateway`
>
> LLD 映射：`08-transaction-and-outbox, 09-concurrency-and-idempotency, 12-observability`
>
> 文档状态：Spec Planning

## 1. 目标

实现 transactional outbox、processed-event 去重和不可缺失 audit record 的基础能力。

## 2. 范围

包含：

- 本 spec 所需 domain/application/infrastructure/interface 设计；
- 对应 persistence、API/event contract、测试和验收标准；
- 与 Tool Gateway LLD 的边界一致性。

不包含：

- Ticket/Workflow state 直接修改；Agent 直连 Tool；secret/raw output 泄漏；绕过 Policy/Approval；跨 domain 分布式事务。

## 3. 核心规则

- 工具执行必须经过 Gateway；状态必须与 Ticket/Workflow 分离；外部副作用必须幂等、可审计、可恢复；事件发布必须走 outbox；事件消费必须 processed-event 去重。
- 本 spec 的实现不得让 Gateway 拥有 Ticket state 或 Workflow state；
- 本 spec 产生的事实必须能追溯到 ticket、workflow、agent task、connector 和 actor。
