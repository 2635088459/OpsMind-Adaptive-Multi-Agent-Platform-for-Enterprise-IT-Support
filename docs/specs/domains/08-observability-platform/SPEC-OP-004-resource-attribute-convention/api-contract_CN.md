# API and Configuration Contract — SPEC-OP-004

> Domain: 08-observability-platform
> Phase: `phase-01-unified-signal-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：固定 service.name/version/namespace、environment、instance.id、telemetry.sdk、tenant 与 cloud/k8s attributes。

## Surfaces

OTLP gRPC/HTTP, Prometheus HTTP/PromQL, Loki HTTP/LogQL, Tempo HTTP/TraceQL, Grafana provisioning/API and Alertmanager API are used only where relevant. Configuration schemas are pinned and validated. Administrative writes require domain-01 identity, scoped role, correlation/idempotency keys, audit and domain-06 approval when risk is high. Stable outcomes distinguish validation, authorization, conflict, dependency unavailable and rollback failure.
