# Phase 09 — 最终验证与发布

> Domain：Policy Approval Governance
>
> Service：`policy-approval-governance-service`
>
> Phase：09
>
> Specs：`SPEC-PG-035` ～ `SPEC-PG-036`
>
> 前置条件：`06-policy-approval-governance` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

完成跨域 contract/e2e harness、最终覆盖审计、release readiness 和剩余风险登记。

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
| 1 | `SPEC-PG-035` | Governance Contract 与 E2E Harness | 14-testing-strategy |
| 2 | `SPEC-PG-036` | 最终覆盖审计与发布准备 | 14-testing-strategy |

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
