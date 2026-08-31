# Acceptance Criteria — SPEC-OP-023

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：实现 5m/1h 与 30m/6h multi-window burn alerts，区分页/工单/通知并验证无告警风暴。

- Configuration validates and deploys reproducibly in local/CI and the documented production topology.
- A real producer signal is ingested, queried and correlated with expected fields.
- Resource, port, volume, retention, authentication and health behavior are evidenced.
- Dependency outage, retry, overload, drop and rollback paths are repeatable and do not block business.
- Secret/PII scan and cardinality budget pass; dashboard/rule/runbook has owner and version.
- Traceability records exact files, commands, results and residual risks.
