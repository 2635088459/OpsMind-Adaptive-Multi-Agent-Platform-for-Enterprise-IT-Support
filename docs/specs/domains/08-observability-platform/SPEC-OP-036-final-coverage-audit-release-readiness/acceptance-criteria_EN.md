# Acceptance Criteria — SPEC-OP-036

> Domain: 08-observability-platform
> Phase: `phase-09-final-verification-release`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Compile coverage, BOM, capacity/security/recovery evidence, risks, upgrade/rollback, and sign-off.

- Configuration validates and deploys reproducibly in local/CI and the documented production topology.
- A real producer signal is ingested, queried and correlated with expected fields.
- Resource, port, volume, retention, authentication and health behavior are evidenced.
- Dependency outage, retry, overload, drop and rollback paths are repeatable and do not block business.
- Secret/PII scan and cardinality budget pass; dashboard/rule/runbook has owner and version.
- Traceability records exact files, commands, results and residual risks.
