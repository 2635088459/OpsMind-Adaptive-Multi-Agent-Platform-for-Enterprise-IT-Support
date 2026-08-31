# Phase 04 — Dashboard 与关联分析

> Domain: Observability Platform
> Phase: 04
> Specs: `SPEC-OP-016` ～ `SPEC-OP-019`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. 目标

把跨域信号组织为可诊断的 dashboard、drill-down 和 exemplar 链路。

## 2. 范围与交付物

包含版本锁定配置、Docker Compose/Kubernetes manifest、节点/端口/volume/资源规格、dashboard/rule/runbook、测试和 traceability；不包含修改业务状态或自研替代成熟遥测后端。

## 3. Specs

| 顺序 | SPEC | 名称 |
|---|---|---|
| 1 | `SPEC-OP-016` | Golden Path 与服务总览 Dashboard |
| 2 | `SPEC-OP-017` | 领域运行 Dashboard |
| 3 | `SPEC-OP-018` | 数据库、Broker 与基础设施 Dashboard |
| 4 | `SPEC-OP-019` | Agent、LLM 成本与容量 Dashboard |

## 4. 实施要求

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. 退出条件

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
