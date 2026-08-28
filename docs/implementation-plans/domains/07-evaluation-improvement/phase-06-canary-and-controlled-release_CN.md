# Phase 06 — Canary 与受控发布

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：06
>
> Specs：`SPEC-EI-027` ～ `SPEC-EI-029`
>
> 前置条件：Phase 05 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 canary plan、online sample evaluation、promotion criteria 和 rollback request。

## 2. 范围

包含：

- CanaryPlan 模型和状态机；
- canary start/pause/expand/succeed/fail API；
- online sample selection；
- canary score aggregation；
- promotion criteria；
- rollback request event。

不包含：

- 实际生产流量路由实现；
- Runtime 配置中心实现；
- 直接执行 rollback。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-027` | Canary Plan 与 Rollout State Machine | 03-state-machine, 09-concurrency-and-idempotency |
| 2 | `SPEC-EI-028` | Online Sample Evaluation | 04-use-cases, 11-security, 12-observability |
| 3 | `SPEC-EI-029` | Promotion Criteria 与 Rollback Request | 06-event-contracts, 10-failure-handling |

## 4. 强制约束

- Canary 必须有流量比例、时间窗、sample size 和 rollback thresholds；
- online sample 必须脱敏；
- rollback condition 触发时不得继续扩流；
- 07 只请求 rollback，由 Runtime/Config owner 执行；
- Canary promotion 必须保留完整 audit trail。

## 5. 退出条件

- Candidate 可进入 Canary lifecycle；
- online sampled scores 能关联 candidate/version；
- rollback requested event 可发布并幂等消费；
- canary expand/promote/rollback race 被测试覆盖。

