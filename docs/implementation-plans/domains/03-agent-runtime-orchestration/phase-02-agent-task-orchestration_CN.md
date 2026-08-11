# Phase 02 — Agent Task Orchestration（Agent Task 编排）

> Domain：Agent Runtime Orchestration
>
> Service：`agent-runtime-service`
>
> Phase：02
>
> Specs：`SPEC-ARO-007` ～ `SPEC-ARO-010`
>
> 前置条件：`03-agent-runtime-orchestration` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

建立 Agent Task、planner task graph、claim lease、completion 和 join policy。

本 Phase 必须保持 Agent Workflow state 与 Ticket state 分离。任何 Ticket 生命周期推进都只能通过 Ticket Workflow 的事件或 command 边界完成。

## 2. 范围

包含：

- 本 Phase 范围内 specs 的设计、代码、migration、测试和 traceability；
- Runtime 自有 aggregate、状态、checkpoint、outbox 或事件处理能力；
- 与 LLD 中相关章节的一致性校验。

不包含：

- Ticket Workflow 主状态机重设计；
- Agent 直接调用 Tool；
- 跨 domain 分布式事务；
- 未经 Tool Gateway 的外部副作用。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-ARO-007` | Agent Task 聚合 | 01-domain-model, 03-state-machine |
| 2 | `SPEC-ARO-008` | Planner Task Graph | 04-use-cases, 13-package-and-class-design |
| 3 | `SPEC-ARO-009` | Task Claim Lease Worker API | 05-api-contracts, 09-concurrency-and-idempotency |
| 4 | `SPEC-ARO-010` | Task Completion 与 Join Policy | 02-business-invariants, 08-transaction-and-outbox |

## 4. 强制约束

- Agent 不能直接调用 Tool，必须经过 Tool Gateway；
- 所有外部副作用前必须有可恢复 checkpoint；
- 所有消费事件必须 processed-event 去重；
- 所有发布事件必须通过 Runtime outbox；
- Pause / Resume command 必须幂等；
- Runtime 崩溃后必须能从 checkpoint、lease、cursor 和 outbox 恢复。

## 5. 退出条件

- 本 Phase 所有 spec 子目录存在，且中英文文档完整；
- 每个 spec 都有验收标准和测试计划；
- 对应 LLD 章节没有未覆盖的关键规则；
- 代码实现时必须能在单 spec 粒度落地、测试和回滚。
