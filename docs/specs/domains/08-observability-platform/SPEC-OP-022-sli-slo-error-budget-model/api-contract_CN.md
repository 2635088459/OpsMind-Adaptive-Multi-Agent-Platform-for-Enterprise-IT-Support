# API and Configuration Contract — SPEC-OP-022

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：为 Ticket API、Workflow、Tool、Approval、Evaluation 定义 SLI、目标、窗口、好/总事件和 error budget。

## Surfaces

OTLP gRPC/HTTP, Prometheus HTTP/PromQL, Loki HTTP/LogQL, Tempo HTTP/TraceQL, Grafana provisioning/API and Alertmanager API are used only where relevant. Configuration schemas are pinned and validated. Administrative writes require domain-01 identity, scoped role, correlation/idempotency keys, audit and domain-06 approval when risk is high. Stable outcomes distinguish validation, authorization, conflict, dependency unavailable and rollback failure.
