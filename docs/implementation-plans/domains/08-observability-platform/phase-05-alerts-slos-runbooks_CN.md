# Phase 05 — 告警、SLO 与 Runbook

> Domain: Observability Platform
> Phase: 05
> Specs: `SPEC-OP-020` ～ `SPEC-OP-024`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. 目标

把 SLI 转成 SLO、error budget、可执行告警、路由和 runbook。

## 2. 范围与交付物

包含版本锁定配置、Docker Compose/Kubernetes manifest、节点/端口/volume/资源规格、dashboard/rule/runbook、测试和 traceability；不包含修改业务状态或自研替代成熟遥测后端。

## 3. Specs

| 顺序 | SPEC | 名称 |
|---|---|---|
| 1 | `SPEC-OP-020` | Recording 与 Alert Rule Catalog |
| 2 | `SPEC-OP-021` | Alertmanager 路由、去重与静默 |
| 3 | `SPEC-OP-022` | SLI、SLO 与 Error Budget 模型 |
| 4 | `SPEC-OP-023` | 多窗口 Burn Rate 告警 |
| 5 | `SPEC-OP-024` | 运维 Runbook Catalog |

## 4. 实施要求

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. 退出条件

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
