# SPEC-OP-033 — 可观测平台自监控

> Domain: 08-observability-platform
> Phase: `phase-08-self-monitoring-recovery-degraded-mode`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

## 目标

本规格的具体目标：监控 accepted/refused/dropped、queue/export、WAL/TSDB、ingestion/compaction、query、notification 与 synthetic probe。

## 交付物

- Version-pinned configuration and environment overlays.
- Deployment manifest with image, ports, health/readiness, CPU/memory, volume and network policy.
- Signal/query/dashboard/rule/runbook artifacts applicable to this objective.
- Security, failure/recovery, rollback, operational and traceability evidence.

## 非目标

No business-state mutation, custom telemetry backend, raw secret/PII ingestion, or unbounded metric labels.
