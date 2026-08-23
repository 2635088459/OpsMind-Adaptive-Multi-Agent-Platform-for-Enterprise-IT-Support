# 06 Policy Approval Governance Implementation Roadmap

> Domain：Policy Approval Governance
>
> Service：`policy-approval-governance-service`
>
> 文档状态：Implementation Roadmap

## 1. 总目标

把 `06-policy-approval-governance` 从 LLD 落成可实现的 phase/spec：为 02/03/04/05 提供统一 policy decision、risk classification、approval lifecycle、职责分离、override guard、governance audit 和 compliance evidence。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| 00 | 工程基础 | `SPEC-PG-001` ～ `SPEC-PG-003` | 建立 policy-approval-governance-service 的服务边界、schema baseline、outbox/processed-event/audit baseline。 |
| 01 | Policy 模型与决策引擎 | `SPEC-PG-004` ～ `SPEC-PG-008` | 实现 Policy/Rule/Version 模型、decision API、rule evaluator、risk mapping 和 constraints 输出。 |
| 02 | 审批生命周期 | `SPEC-PG-009` ～ `SPEC-PG-013` | 实现 ApprovalRequest、grant/deny/cancel/expire、approval decision finality 和事件发布。 |
| 03 | 安全与职责分离 | `SPEC-PG-014` ～ `SPEC-PG-017` | 实现 RBAC/ABAC、职责分离、approval authenticity、MFA/step-up marker 和 override guard。 |
| 04 | Policy 管理与版本化 | `SPEC-PG-018` ～ `SPEC-PG-021` | 实现 policy draft/review/publish/deprecate/supersede、版本不可变和 policy cache 刷新。 |
| 05 | Override 与例外治理 | `SPEC-PG-022` ～ `SPEC-PG-024` | 实现高风险 override、SLA/ticket exception、admin repair approval 和范围化撤销。 |
| 06 | 跨域契约闭环 | `SPEC-PG-025` ～ `SPEC-PG-028` | 闭环 05 Tool Gateway、03 Agent Runtime、02 Ticket Workflow、04 Memory Knowledge 的 governance 契约。 |
| 07 | 可观测性、审计与合规 | `SPEC-PG-029` ～ `SPEC-PG-031` | 补齐 metrics/logs/traces、governance audit query、audit integrity 和 compliance reporting。 |
| 08 | 失败恢复与降级模式 | `SPEC-PG-032` ～ `SPEC-PG-034` | 实现 evaluator failure、approval expiry worker、poison decision、outbox replay 和 fail-closed degraded mode。 |
| 09 | 最终验证与发布 | `SPEC-PG-035` ～ `SPEC-PG-036` | 完成跨域 contract/e2e harness、最终覆盖审计、release readiness 和剩余风险登记。 |

## 3. 闭环原则

- 06 只产生 governance facts，不执行工具、不改 Ticket state、不改 Workflow state、不写 Memory。
- 每个 decision 必须保存 policy version、input hash、reason codes 和 constraints。
- 每个 approval final decision 必须校验 approver 权限、职责分离和 request linkage。
- Denied、Expired、Cancelled、Policy Denied 必须保持不同语义。
- Policy published version 不可变，规则修复必须发布新版本。
- 所有治理状态迁移必须同事务写 audit 和 outbox。
- 所有消费事件必须 processed-event 去重。
