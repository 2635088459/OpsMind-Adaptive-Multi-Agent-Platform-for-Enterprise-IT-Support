# Phase 05 — Improvement Candidate Lifecycle

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：05
>
> Specs：`SPEC-EI-023` ～ `SPEC-EI-026`
>
> 前置条件：Phase 04 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 failure clustering、candidate generation、benchmark binding、06 approval request 和 candidate finality。

## 2. 范围

包含：

- failure cluster 模型；
- prompt/routing/tool schema hint/memory retrieval config/verification checklist candidate；
- candidate lifecycle API；
- candidate benchmark run 绑定；
- 06 release approval request；
- candidate rejected/approved event。

不包含：

- 直接写生产 prompt/config；
- Canary traffic control；
- Runtime 配置存储所有权迁移。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-023` | Failure Clustering 与 Root Cause Taxonomy | 04-use-cases, 12-observability |
| 2 | `SPEC-EI-024` | Improvement Candidate 聚合与状态机 | 01-domain-model, 03-state-machine |
| 3 | `SPEC-EI-025` | Candidate Benchmark Binding 与 Gate Enforcement | 02-business-invariants, 08-transaction-and-outbox |
| 4 | `SPEC-EI-026` | Policy Approval Release Contract | 05-api-contracts, 06-event-contracts, 11-security |

## 4. 强制约束

- Candidate 只能表达 proposed change，不能直接应用生产变更；
- Candidate 必须绑定 source failures、source run、benchmark result 和 gate report；
- creator 不能 approve 自己 candidate；
- approval denied/expired/cancelled 必须阻断 candidate；
- approved 只表示可进入 Canary，不表示已全量上线。

## 5. 退出条件

- failure cluster 可从 failed run 生成；
- candidate API 支持 draft、benchmark、approval request、reject；
- 与 06 approval contract 有测试；
- candidate finality 和 audit/outbox 被测试覆盖。

