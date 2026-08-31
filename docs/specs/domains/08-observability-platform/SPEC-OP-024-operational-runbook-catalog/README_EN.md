# SPEC-OP-024 — Operational Runbook Catalog

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

## Goal

Concrete objective: Require every paging alert to link an owned runbook with diagnosis, mitigation, recovery, and escalation.

## Deliverables

- Version-pinned configuration and environment overlays.
- Deployment manifest with image, ports, health/readiness, CPU/memory, volume and network policy.
- Signal/query/dashboard/rule/runbook artifacts applicable to this objective.
- Security, failure/recovery, rollback, operational and traceability evidence.

## Non-goals

No business-state mutation, custom telemetry backend, raw secret/PII ingestion, or unbounded metric labels.
