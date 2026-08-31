# 08 Observability Platform Phase / Spec Coverage Matrix

## 目标

确认 10 个 Phase、36 个 SPEC 覆盖 14 个 LLD 切面，并闭环 01–07 与平台基础设施。

| Phase | Specs | 闭环目标 |
|---|---|---|
| 00 平台工程基础 | `SPEC-OP-001` ～ `SPEC-OP-003` | 建立可重复部署的本地、CI 与生产拓扑，明确组件、端口、volume、配置、secret 和版本边界。 |
| 01 统一信号契约 | `SPEC-OP-004` ～ `SPEC-OP-007` | 让所有应用与基础设施产生可关联、低基数、已脱敏的统一 signals。 |
| 02 Collector 接入与处理 | `SPEC-OP-008` ～ `SPEC-OP-011` | 建立高可用 OTLP 接入、处理、采样、路由和过载保护数据面。 |
| 03 遥测后端与保留 | `SPEC-OP-012` ～ `SPEC-OP-015` | 部署并治理 metrics/logs/traces 后端、存储设备、容量、压缩、备份和保留。 |
| 04 Dashboard 与关联分析 | `SPEC-OP-016` ～ `SPEC-OP-019` | 把跨域信号组织为可诊断的 dashboard、drill-down 和 exemplar 链路。 |
| 05 告警、SLO 与 Runbook | `SPEC-OP-020` ～ `SPEC-OP-024` | 把 SLI 转成 SLO、error budget、可执行告警、路由和 runbook。 |
| 06 跨域契约闭环 | `SPEC-OP-025` ～ `SPEC-OP-029` | 逐域验证真实 producer 信号以及数据库、broker、connector exporter。 |
| 07 安全、隐私与配置治理 | `SPEC-OP-030` ～ `SPEC-OP-032` | 限制观测数据访问，阻止 secret/PII 泄漏，并治理配置变更。 |
| 08 自监控、恢复与降级 | `SPEC-OP-033` ～ `SPEC-OP-034` | 观测平台自身可观测，在故障和资源耗尽时有界降级并可恢复。 |
| 09 最终验证与发布 | `SPEC-OP-035` ～ `SPEC-OP-036` | 证明完整工单 trace、告警和恢复链路可运行并完成发布证据。 |

## LLD Coverage

| LLD Section | Specs |
|---|---|
| 01-domain-model | `SPEC-OP-010`, `SPEC-OP-020`, `SPEC-OP-030`, `SPEC-OP-035`, `SPEC-OP-036` |
| 02-business-invariants | `SPEC-OP-001`, `SPEC-OP-011`, `SPEC-OP-021`, `SPEC-OP-031`, `SPEC-OP-035`, `SPEC-OP-036` |
| 03-state-machine | `SPEC-OP-002`, `SPEC-OP-012`, `SPEC-OP-022`, `SPEC-OP-032`, `SPEC-OP-035`, `SPEC-OP-036` |
| 04-use-cases | `SPEC-OP-003`, `SPEC-OP-013`, `SPEC-OP-023`, `SPEC-OP-033`, `SPEC-OP-035`, `SPEC-OP-036` |
| 05-api-contracts | `SPEC-OP-004`, `SPEC-OP-014`, `SPEC-OP-024`, `SPEC-OP-034`, `SPEC-OP-035`, `SPEC-OP-036` |
| 06-event-contracts | `SPEC-OP-005`, `SPEC-OP-015`, `SPEC-OP-025`, `SPEC-OP-035`, `SPEC-OP-036` |
| 07-data-model | `SPEC-OP-006`, `SPEC-OP-016`, `SPEC-OP-026`, `SPEC-OP-035`, `SPEC-OP-036` |
| 08-transaction-and-outbox | `SPEC-OP-007`, `SPEC-OP-017`, `SPEC-OP-027`, `SPEC-OP-035`, `SPEC-OP-036` |
| 09-concurrency-and-idempotency | `SPEC-OP-008`, `SPEC-OP-018`, `SPEC-OP-028`, `SPEC-OP-035`, `SPEC-OP-036` |
| 10-failure-handling | `SPEC-OP-009`, `SPEC-OP-019`, `SPEC-OP-029`, `SPEC-OP-035`, `SPEC-OP-036` |
| 11-security | `SPEC-OP-010`, `SPEC-OP-020`, `SPEC-OP-030`, `SPEC-OP-035`, `SPEC-OP-036` |
| 12-observability | `SPEC-OP-001`, `SPEC-OP-011`, `SPEC-OP-021`, `SPEC-OP-031`, `SPEC-OP-035`, `SPEC-OP-036` |
| 13-package-and-class-design | `SPEC-OP-002`, `SPEC-OP-012`, `SPEC-OP-022`, `SPEC-OP-032`, `SPEC-OP-035`, `SPEC-OP-036` |
| 14-testing-strategy | `SPEC-OP-003`, `SPEC-OP-013`, `SPEC-OP-023`, `SPEC-OP-033`, `SPEC-OP-035`, `SPEC-OP-036` |

## 最终标准

- Full Identity/MFA lifecycle trace is searchable by trace/correlation ID.
- Metrics, logs, traces, dashboards, alerts, SLOs, runbooks, privacy, recovery, and release evidence are complete.
- Domain 08 never owns or mutates business facts.
