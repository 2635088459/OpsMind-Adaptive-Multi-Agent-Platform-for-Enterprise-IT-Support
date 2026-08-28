# Phase 08 — 安全、观测与恢复

> Domain：Evaluation Improvement
>
> Service：`evaluation-improvement-service`
>
> Phase：08
>
> Specs：`SPEC-EI-034` ～ `SPEC-EI-035`
>
> 前置条件：Phase 07 完成
>
> 文档状态：Implementation Plan

## 1. Phase 目标

补齐 RBAC/ABAC、redaction、metrics/logs/traces、LangSmith outage、poison event 和 recovery。

## 2. 范围

包含：

- evaluation role model 和 sensitive evidence access；
- dataset/report/log redaction；
- metrics/logs/traces/alerts；
- LangSmith outage degraded mode；
- grader failure、partial run、poison event；
- admin repair/replay；
- recovery scanners。

不包含：

- 08 Observability Platform 的存储实现；
- 01 Identity 的 role source 管理；
- 生产流量控制器实现。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-EI-034` | Evaluation Security、Redaction 与 Observability | 11-security, 12-observability |
| 2 | `SPEC-EI-035` | LangSmith/Grader/Outbox Failure Recovery | 10-failure-handling, 08-transaction-and-outbox, 09-concurrency-and-idempotency |

## 4. 强制约束

- 写 API 必须校验 01 identity；
- sensitive evidence access 必须审计；
- raw secret 不得进入 log/metric/report；
- offline gate 对关键依赖故障 fail closed；
- telemetry 对线上业务 fail open。

## 5. 退出条件

- security tests、redaction tests、observability assertions 完成；
- LangSmith outage、grader error、partial run、poison event、outbox replay 都有恢复测试；
- admin repair/replay API 有审计；
- release dashboards 和 alerts 字段齐全。

