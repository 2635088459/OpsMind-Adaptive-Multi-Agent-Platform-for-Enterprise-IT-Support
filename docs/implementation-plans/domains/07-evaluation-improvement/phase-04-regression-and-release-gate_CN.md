# Phase 04 — Regression 与 Release Gate

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：04
>
> Specs：`SPEC-EI-019` ～ `SPEC-EI-022`
>
> 前置条件：Phase 03 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 baseline comparison、gate policy、critical case enforcement、regression report 和 CI gate。

## 2. 范围

包含：

- RegressionComparator；
- release gate policy/version；
- critical case fail-fast；
- metric diff 和 threshold 计算；
- regression report API；
- CI 调用入口和 gate failed event。

不包含：

- Candidate generation；
- 06 approval；
- Canary rollout；
- 生产配置修改。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-019` | Baseline Run 与 Regression Comparator | 01-domain-model, 09-concurrency-and-idempotency |
| 2 | `SPEC-EI-020` | Release Gate Policy 与 Critical Cases | 02-business-invariants, 04-use-cases |
| 3 | `SPEC-EI-021` | Regression Report API 与 Event | 05-api-contracts, 06-event-contracts |
| 4 | `SPEC-EI-022` | CI Evaluation Gate Harness | 14-testing-strategy, 12-observability |

## 4. 强制约束

- Baseline 必须是具体 run id，不能是移动中的 latest；
- critical case 任一失败 gate failed；
- policy violation、forbidden tool、unauthorized memory access 必须为 0；
- token cost/latency 不能覆盖安全与正确性失败；
- report finalization 必须同事务写 audit/outbox。

## 5. 退出条件

- Candidate/baseline comparison 可复现；
- report API 返回 gate results、metric diffs、critical failures；
- CI 能以非零退出或 failed status 阻止 promotion；
- regression detected/gate passed/failed events 可发布并测试。

