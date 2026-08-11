# SPEC-ARO-002 — Agent Runtime Schema Baseline（Agent Runtime Schema 基线）

> 领域：Agent Runtime Orchestration
>
> Phase：00 — 工程基础
>
> 服务：`agent-runtime-service`
>
> LLD 映射：`07-data-model`
>
> 文档状态：Spec Planning

## 1. 目标

实现 `Agent Runtime Schema 基线`，并保持 Runtime 只拥有 Agent Workflow state，不直接修改 Ticket state。

## 2. 范围

包含：

- 本 spec 所需 domain/application/infrastructure/interface 设计；
- 对应 persistence、API/event contract、测试和验收标准；
- 与 `docs/low-level-design/domains/03-agent-runtime-orchestration` 中映射章节的一致性。

不包含：

- Ticket Workflow 状态机重设计；
- Agent 直接调用 Tool；
- 跨 domain 分布式事务；
- 未列入本 spec 的后续 phase 能力。

## 3. 核心规则

- 所有写操作必须有幂等或版本保护；
- 所有发布事件必须通过 Runtime outbox；
- 所有消费事件必须使用 processed-event 去重；
- 任何工具副作用必须经过 Tool Gateway；
- 崩溃恢复路径必须可由 checkpoint、cursor、lease 或 outbox 推导。
