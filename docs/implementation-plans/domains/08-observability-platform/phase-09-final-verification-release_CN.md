# Phase 09 — 最终验证与发布

> Domain: Observability Platform
> Phase: 09
> Specs: `SPEC-OP-035` ～ `SPEC-OP-036`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. 目标

证明完整工单 trace、告警和恢复链路可运行并完成发布证据。

## 2. 范围与交付物

包含版本锁定配置、Docker Compose/Kubernetes manifest、节点/端口/volume/资源规格、dashboard/rule/runbook、测试和 traceability；不包含修改业务状态或自研替代成熟遥测后端。

## 3. Specs

| 顺序 | SPEC | 名称 |
|---|---|---|
| 1 | `SPEC-OP-035` | 全生命周期 Trace E2E 与故障演练 |
| 2 | `SPEC-OP-036` | 最终覆盖审计与发布准备 |

## 4. 实施要求

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. 退出条件

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
