# Acceptance Criteria — SPEC-OP-003

> Domain: 08-observability-platform
> Phase: `phase-00-platform-engineering-foundation`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：定义 signal allow/deny 字段、owner、retention class、cardinality budget、schema review 和例外流程。

- Configuration validates and deploys reproducibly in local/CI and the documented production topology.
- A real producer signal is ingested, queried and correlated with expected fields.
- Resource, port, volume, retention, authentication and health behavior are evidenced.
- Dependency outage, retry, overload, drop and rollback paths are repeatable and do not block business.
- Secret/PII scan and cardinality budget pass; dashboard/rule/runbook has owner and version.
- Traceability records exact files, commands, results and residual risks.
