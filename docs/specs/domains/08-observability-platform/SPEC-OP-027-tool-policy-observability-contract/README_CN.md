# SPEC-OP-027 — Tool 与 Policy 观测契约

> Domain: 08-observability-platform
> Phase: `phase-06-cross-domain-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

## 目标

本规格的具体目标：锁定 tool execution/connector/side effect 与 policy decision/approval/override/audit 的 signals。

## 交付物

- Version-pinned configuration and environment overlays.
- Deployment manifest with image, ports, health/readiness, CPU/memory, volume and network policy.
- Signal/query/dashboard/rule/runbook artifacts applicable to this objective.
- Security, failure/recovery, rollback, operational and traceability evidence.

## 非目标

No business-state mutation, custom telemetry backend, raw secret/PII ingestion, or unbounded metric labels.
