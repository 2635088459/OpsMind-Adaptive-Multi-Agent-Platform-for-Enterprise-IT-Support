# Phase 02 — 审批生命周期

> Domain：Policy Approval Governance
>
> Service：`policy-approval-governance-service`
>
> Phase：02
>
> Specs：`SPEC-PG-009` ～ `SPEC-PG-013`
>
> 前置条件：`06-policy-approval-governance` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 ApprovalRequest、grant/deny/cancel/expire、approval decision finality 和事件发布。

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
| 1 | `SPEC-PG-009` | Approval Request 聚合 | 01-domain-model, 03-state-machine, 07-data-model |
| 2 | `SPEC-PG-010` | Approval Request API 与事件 | 05-api-contracts, 06-event-contracts, 08-transaction-and-outbox |
| 3 | `SPEC-PG-011` | Approval Grant/Deny API | 05-api-contracts, 03-state-machine, 09-concurrency-and-idempotency |
| 4 | `SPEC-PG-012` | Approval Expiry 与 Cancel | 03-state-machine, 10-failure-handling, 08-transaction-and-outbox |
| 5 | `SPEC-PG-013` | Approval Decision Event Publication | 06-event-contracts, 08-transaction-and-outbox |

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
