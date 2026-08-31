# Acceptance Criteria — SPEC-OP-001

> Domain: 08-observability-platform
> Phase: `phase-00-platform-engineering-foundation`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：定义数据面/控制面所有权、禁止业务写入、组件责任矩阵和 ADR。

- Configuration validates and deploys reproducibly in local/CI and the documented production topology.
- A real producer signal is ingested, queried and correlated with expected fields.
- Resource, port, volume, retention, authentication and health behavior are evidenced.
- Dependency outage, retry, overload, drop and rollback paths are repeatable and do not block business.
- Secret/PII scan and cardinality budget pass; dashboard/rule/runbook has owner and version.
- Traceability records exact files, commands, results and residual risks.
