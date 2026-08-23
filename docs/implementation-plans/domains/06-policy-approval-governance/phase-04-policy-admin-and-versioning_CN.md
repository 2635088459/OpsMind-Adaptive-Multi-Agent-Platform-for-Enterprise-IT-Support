# Phase 04 — Policy 管理与版本化

> Domain：Policy Approval Governance
>
> Service：`policy-approval-governance-service`
>
> Phase：04
>
> Specs：`SPEC-PG-018` ～ `SPEC-PG-021`
>
> 前置条件：`06-policy-approval-governance` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 policy draft/review/publish/deprecate/supersede、版本不可变和 policy cache 刷新。

## 2. 范围

包含：

- 本 Phase 范围内 specs 的设计、代码、migration、测试和 traceability；
- Policy Approval Governance 自有 aggregate、API、event、outbox、rule evaluator、approval worker 或 audit 能力；
- 与 02/03/04/05 的契约闭环校验。

不包含：

- Tool 直接执行；
- Ticket Workflow 主状态机迁移；
- Agent Runtime Workflow state 迁移；
- Memory 内容写入；
- 跨 domain 分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-PG-018` | Policy Draft/Review/Publish | 03-state-machine, 05-api-contracts, 11-security |
| 2 | `SPEC-PG-019` | Policy Version 不可变 | 02-business-invariants, 07-data-model |
| 3 | `SPEC-PG-020` | Policy Deprecate/Supersede/Archive | 03-state-machine, 06-event-contracts |
| 4 | `SPEC-PG-021` | Policy Cache Refresh Contract | 06-event-contracts, 10-failure-handling |

## 4. 强制约束

- 06 只能输出 governance facts；
- policy decision 必须有 policy version 和 input hash；
- approval decision 必须校验权限、职责分离和 request linkage；
- 所有发布事件必须通过 Governance outbox；
- 所有消费事件必须 processed-event 去重。

## 5. 退出条件

- 本 Phase 所有 spec 子目录存在，且中英文文档完整；
- 每个 spec 都有验收标准和测试计划；
- 对应 LLD 章节没有未覆盖的关键规则；
- 与相关上游/下游域的契约在测试计划中可验证。
