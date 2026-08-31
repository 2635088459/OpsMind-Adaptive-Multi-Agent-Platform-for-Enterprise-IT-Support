# 10-failure-handling — 失败处理与降级

> Domain: Observability Platform
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Detailed LLD

## 目标

本节定义遥测数据面与运维控制面的失败处理与降级。核心资产包括 TelemetrySource、SignalContract、CollectorPipeline、BackendTenant、DashboardDefinition、AlertRule、SloDefinition、RunbookReference、RetentionPolicy、Silence 和 ConfigurationRelease；每项都必须有 owner、environment、version、labels、lifecycle、access policy、audit reference 与 rollback reference。

## 详细设计要求

每项设计必须明确组件、部署节点、端口、volume、CPU/内存、retention、认证、环境 overlay、失败行为、运维命令与测试证据。信号是不可变观测事实；配置变更必须版本化和审计；告警不能直接修改业务状态。

定义 Collector unavailable、backend unavailable、disk full、cardinality explosion、export timeout、queue overflow、corrupt block、alert delivery failure 的 fail-open-to-business、有界缓冲、drop accounting 和恢复。

## 跨域边界

08 consumes telemetry from 01–07 and infrastructure, preserves source-domain semantic ownership, and provides query/dashboard/alert evidence only.
