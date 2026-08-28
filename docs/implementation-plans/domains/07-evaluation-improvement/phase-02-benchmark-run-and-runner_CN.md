# Phase 02 — Benchmark Run 与执行器

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：02
>
> Specs：`SPEC-EI-009` ～ `SPEC-EI-013`
>
> 前置条件：Phase 01 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

实现 EvaluationRun、case runner、Agent Runtime evaluation endpoint 调用、LangSmith experiment linkage 和 run lifecycle。

## 2. 范围

包含：

- EvaluationRun 聚合和状态机；
- run create/cancel/query API；
- case execution worker、lease、retry 和 stale result guard；
- Agent Runtime evaluation client；
- LangSmith dataset/experiment adapter；
- workflow trace、tool trajectory、retrieval evidence 的 artifact linkage。

不包含：

- Grader 评分细节；
- release gate policy；
- improvement candidate；
- Canary rollout。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-009` | Evaluation Run 聚合与状态机 | 01-domain-model, 03-state-machine |
| 2 | `SPEC-EI-010` | Run Create/Cancel/Query API | 05-api-contracts, 09-concurrency-and-idempotency |
| 3 | `SPEC-EI-011` | Case Runner Worker、Lease 与 Retry | 04-use-cases, 10-failure-handling |
| 4 | `SPEC-EI-012` | Agent Runtime Evaluation Client Contract | 05-api-contracts, 14-testing-strategy |
| 5 | `SPEC-EI-013` | LangSmith Experiment Linkage | 07-data-model, 12-observability |

## 4. 强制约束

- Run 必须绑定 dataset version、target version、baseline version、grader bundle version；
- 同一 `runKey` 重复提交必须幂等；
- stale case result 不得进入评分；
- LangSmith 故障时 release gate fail closed；
- 07 不推进真实 workflow state。

## 5. 退出条件

- Offline benchmark run 能从 API 创建并由 worker 执行；
- run/case 状态、artifact refs 和 idempotency tests 完成；
- Agent Runtime evaluation contract 有 mock/integration 测试；
- LangSmith outage/partial run 行为被测试覆盖。

