# SPEC-OP-005 — HTTP 与 AMQP Trace 传播

> Domain: 08-observability-platform
> Phase: `phase-01-unified-signal-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

## 目标

本规格的具体目标：实现 W3C traceparent/tracestate/baggage 在 HTTP 与 RabbitMQ publish/consume 的注入提取，禁止敏感 baggage。

## 交付物

- Version-pinned configuration and environment overlays.
- Deployment manifest with image, ports, health/readiness, CPU/memory, volume and network policy.
- Signal/query/dashboard/rule/runbook artifacts applicable to this objective.
- Security, failure/recovery, rollback, operational and traceability evidence.

## 非目标

No business-state mutation, custom telemetry backend, raw secret/PII ingestion, or unbounded metric labels.
