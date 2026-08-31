# Phase 00 — 平台工程基础

> Domain: Observability Platform
> Phase: 00
> Specs: `SPEC-OP-001` ～ `SPEC-OP-003`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. 目标

建立可重复部署的本地、CI 与生产拓扑，明确组件、端口、volume、配置、secret 和版本边界。

## 2. 范围与交付物

包含版本锁定配置、Docker Compose/Kubernetes manifest、节点/端口/volume/资源规格、dashboard/rule/runbook、测试和 traceability；不包含修改业务状态或自研替代成熟遥测后端。

## 3. Specs

| 顺序 | SPEC | 名称 |
|---|---|---|
| 1 | `SPEC-OP-001` | 平台边界与仓库布局 |
| 2 | `SPEC-OP-002` | 本地可观测拓扑 |
| 3 | `SPEC-OP-003` | 遥测治理基线 |

## 4. 实施要求

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. 退出条件

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
