# Phase 00 — 工程基础

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：00
>
> Specs：`SPEC-EI-001` ～ `SPEC-EI-003`
>
> 前置条件：`07-evaluation-improvement` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

建立 evaluation-improvement-service 的服务边界、schema baseline、outbox/processed-event/audit baseline。

## 2. 范围

包含：

- 本 Phase 范围内 specs 的设计、代码、migration、测试和 traceability；
- Evaluation Improvement 自有 aggregate、API、event、outbox、worker、grader 或 audit 能力；
- 与 02/03/04/05/06/08 的契约闭环校验。

不包含：

- 生产 Agent/Prompt 直接修改；
- Ticket Workflow 主状态机迁移；
- Agent Runtime Workflow state 迁移；
- Tool 执行或 Connector 管理；
- Memory 内容写入；
- Policy/Approval 规则所有权迁移；
- 跨 domain 分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-001` | Evaluation 模块与包边界 | 13-package-and-class-design, 02-business-invariants |
| 2 | `SPEC-EI-002` | Evaluation schema baseline | 07-data-model, 03-state-machine |
| 3 | `SPEC-EI-003` | Outbox、Processed Event 与审计 baseline | 08-transaction-and-outbox, 09-concurrency-and-idempotency, 12-observability |

## 4. 强制约束

- 07 不能直接修改生产 Agent、Prompt、Policy、Tool、Ticket、Workflow 或 Memory；
- 所有 evaluation facts 必须绑定 source、version、hash 和 correlation id；
- release gate 失败时 candidate 不得进入审批或发布；
- 安全门禁不能只依赖 LLM Judge；
- 所有发布事件必须通过 Evaluation outbox。

## 5. 退出条件

- 本 Phase 所有 spec 子目录存在，且中英文文档完整；
- 每个 spec 都有验收标准和测试计划；
- 对应 LLD 章节没有未覆盖的关键规则；
- 与相关上游/下游域的契约在测试计划中可验证。

