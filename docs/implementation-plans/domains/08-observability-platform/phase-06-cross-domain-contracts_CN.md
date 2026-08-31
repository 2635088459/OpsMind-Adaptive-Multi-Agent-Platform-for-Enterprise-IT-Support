# Phase 06 — 跨域契约闭环

> Domain: Observability Platform
> Phase: 06
> Specs: `SPEC-OP-025` ～ `SPEC-OP-029`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. 目标

逐域验证真实 producer 信号以及数据库、broker、connector exporter。

## 2. 范围与交付物

包含版本锁定配置、Docker Compose/Kubernetes manifest、节点/端口/volume/资源规格、dashboard/rule/runbook、测试和 traceability；不包含修改业务状态或自研替代成熟遥测后端。

## 3. Specs

| 顺序 | SPEC | 名称 |
|---|---|---|
| 1 | `SPEC-OP-025` | Identity 与 Ticket 观测契约 |
| 2 | `SPEC-OP-026` | Runtime 与 Memory 观测契约 |
| 3 | `SPEC-OP-027` | Tool 与 Policy 观测契约 |
| 4 | `SPEC-OP-028` | Evaluation 观测契约 |
| 5 | `SPEC-OP-029` | PostgreSQL、RabbitMQ 与 Connector 契约 |

## 4. 实施要求

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. 退出条件

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
