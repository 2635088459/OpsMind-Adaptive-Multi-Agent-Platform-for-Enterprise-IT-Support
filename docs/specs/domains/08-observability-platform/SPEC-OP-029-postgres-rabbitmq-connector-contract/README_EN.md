# SPEC-OP-029 — PostgreSQL RabbitMQ And Connector Contract

> Domain: 08-observability-platform
> Phase: `phase-06-cross-domain-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

## Goal

Concrete objective: Integrate infrastructure/connector exporters and standardize health, lag, retry, limit, and dependency spans.

## Deliverables

- Version-pinned configuration and environment overlays.
- Deployment manifest with image, ports, health/readiness, CPU/memory, volume and network policy.
- Signal/query/dashboard/rule/runbook artifacts applicable to this objective.
- Security, failure/recovery, rollback, operational and traceability evidence.

## Non-goals

No business-state mutation, custom telemetry backend, raw secret/PII ingestion, or unbounded metric labels.
