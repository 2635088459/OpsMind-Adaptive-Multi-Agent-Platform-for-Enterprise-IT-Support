# API and Configuration Contract — SPEC-OP-007

> Domain: 08-observability-platform
> Phase: `phase-01-unified-signal-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：定义 JSON log schema、trace/correlation linkage、severity、event code、字段脱敏、采样与 multiline 处理。

## Surfaces

OTLP gRPC/HTTP, Prometheus HTTP/PromQL, Loki HTTP/LogQL, Tempo HTTP/TraceQL, Grafana provisioning/API and Alertmanager API are used only where relevant. Configuration schemas are pinned and validated. Administrative writes require domain-01 identity, scoped role, correlation/idempotency keys, audit and domain-06 approval when risk is high. Stable outcomes distinguish validation, authorization, conflict, dependency unavailable and rollback failure.
