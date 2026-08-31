# 08 Observability Platform Implementation Roadmap

> Domain：Observability & Platform Infrastructure  
> Deployables：OpenTelemetry Collector、Prometheus、Loki、Tempo、Grafana、Alertmanager  
> 文档状态：Implementation Roadmap

## 1. 总目标

建立统一、低泄漏、可恢复的遥测平台，使 Portal/API Gateway、01–07、RabbitMQ、PostgreSQL 与 Connector 的 metrics/logs/traces 可关联查询，并把平台与业务 SLI 转成可执行告警、SLO 和 error budget。08 只管理观测事实和配置，不改变任何业务领域状态。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| 00 | 平台工程基础 | `SPEC-OP-001`～`003` | 确定部署边界、配置仓库、版本/环境与遥测治理基线。 |
| 01 | 统一信号契约 | `SPEC-OP-004`～`007` | 统一 resource attributes、HTTP/AMQP trace propagation、metrics naming、structured logging/redaction。 |
| 02 | Collector 接入与处理 | `SPEC-OP-008`～`011` | 实现 OTLP gateway、processor/routing、sampling、batch/retry/backpressure。 |
| 03 | 遥测后端与保留 | `SPEC-OP-012`～`015` | 落地 Prometheus、Loki、Tempo、索引/compaction/retention。 |
| 04 | Dashboard 与关联分析 | `SPEC-OP-016`～`019` | 建立 Golden Path、领域、基础设施、成本容量 dashboard 和 logs↔traces↔metrics 跳转。 |
| 05 | Alert、SLO 与 Runbook | `SPEC-OP-020`～`024` | 建立规则、路由去重、SLO/error budget、burn-rate alert 与 runbook。 |
| 06 | 跨域契约闭环 | `SPEC-OP-025`～`029` | 闭环 01–07、RabbitMQ、PostgreSQL、外部 Connector 的观测信号。 |
| 07 | 安全、隐私与配置治理 | `SPEC-OP-030`～`032` | 实现访问控制、遥测脱敏/多租户隔离、配置变更审批和审计。 |
| 08 | 自监控、失败恢复与降级 | `SPEC-OP-033`～`034` | 监控观测平台自身，处理后端/Collector 故障、积压、丢弃和恢复。 |
| 09 | 最终验证与发布 | `SPEC-OP-035`～`036` | 完成全生命周期 trace E2E、故障演练、覆盖审计与 release readiness。 |

## 3. 完成原则

- 一次 Identity/MFA 工单必须能按 trace/correlation ID 关联到跨域证据。
- 遥测平台故障不得阻断工单与 Agent 核心业务。
- Secret、原始 Prompt、用户文本和未脱敏 PII 不得进入遥测。
- 08 不将告警或 dashboard 结果写回业务状态。
- Production 配置必须版本化、review、可回滚且有审计。
