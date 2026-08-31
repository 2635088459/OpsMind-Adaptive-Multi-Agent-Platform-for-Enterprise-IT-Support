# SPEC-OP-008 — OTLP Collector Gateway

> Domain: 08-observability-platform
> Phase: `phase-02-collector-intake-processing`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

## 目标

本规格的具体目标：部署 OTLP gRPC 4317/HTTP 4318 gateway、TLS/auth、health 13133、metrics 8888 和 readiness。

## 交付物

- Version-pinned configuration and environment overlays.
- Deployment manifest with image, ports, health/readiness, CPU/memory, volume and network policy.
- Signal/query/dashboard/rule/runbook artifacts applicable to this objective.
- Security, failure/recovery, rollback, operational and traceability evidence.

## 非目标

No business-state mutation, custom telemetry backend, raw secret/PII ingestion, or unbounded metric labels.
