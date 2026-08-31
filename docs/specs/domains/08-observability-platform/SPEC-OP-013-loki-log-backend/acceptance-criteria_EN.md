# Acceptance Criteria — SPEC-OP-013

> Domain: 08-observability-platform
> Phase: `phase-03-telemetry-backends-retention`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Deploy Loki write/read, schema, index/chunks, tenants, limits, retention, LogQL, and metadata.

- Configuration validates and deploys reproducibly in local/CI and the documented production topology.
- A real producer signal is ingested, queried and correlated with expected fields.
- Resource, port, volume, retention, authentication and health behavior are evidenced.
- Dependency outage, retry, overload, drop and rollback paths are repeatable and do not block business.
- Secret/PII scan and cardinality budget pass; dashboard/rule/runbook has owner and version.
- Traceability records exact files, commands, results and residual risks.
