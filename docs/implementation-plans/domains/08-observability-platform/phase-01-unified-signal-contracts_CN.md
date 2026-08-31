# Phase 01 — 统一信号契约

> Domain: Observability Platform
> Phase: 01
> Specs: `SPEC-OP-004` ～ `SPEC-OP-007`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. 目标

让所有应用与基础设施产生可关联、低基数、已脱敏的统一 signals。

## 2. 范围与交付物

包含版本锁定配置、Docker Compose/Kubernetes manifest、节点/端口/volume/资源规格、dashboard/rule/runbook、测试和 traceability；不包含修改业务状态或自研替代成熟遥测后端。

## 3. Specs

| 顺序 | SPEC | 名称 |
|---|---|---|
| 1 | `SPEC-OP-004` | Resource Attribute 规范 |
| 2 | `SPEC-OP-005` | HTTP 与 AMQP Trace 传播 |
| 3 | `SPEC-OP-006` | 指标命名与基数契约 |
| 4 | `SPEC-OP-007` | 结构化日志与脱敏契约 |

## 4. 实施要求

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. 退出条件

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
