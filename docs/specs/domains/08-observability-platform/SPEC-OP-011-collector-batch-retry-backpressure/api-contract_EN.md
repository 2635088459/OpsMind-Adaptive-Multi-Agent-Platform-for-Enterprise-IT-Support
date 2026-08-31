# API and Configuration Contract — SPEC-OP-011

> Domain: 08-observability-platform
> Phase: `phase-02-collector-intake-processing`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Configure batch, queues, retries, WAL/file storage, throttling, drop accounting, and load budgets.

## Surfaces

OTLP gRPC/HTTP, Prometheus HTTP/PromQL, Loki HTTP/LogQL, Tempo HTTP/TraceQL, Grafana provisioning/API and Alertmanager API are used only where relevant. Configuration schemas are pinned and validated. Administrative writes require domain-01 identity, scoped role, correlation/idempotency keys, audit and domain-06 approval when risk is high. Stable outcomes distinguish validation, authorization, conflict, dependency unavailable and rollback failure.
