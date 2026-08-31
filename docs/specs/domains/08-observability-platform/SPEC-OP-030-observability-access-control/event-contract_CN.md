# Signal and Event Contract — SPEC-OP-030

> Domain: 08-observability-platform
> Phase: `phase-07-security-privacy-config-governance`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：Grafana/Otel endpoint 接入 01 OIDC/RBAC，角色 Viewer/Editor/SRE/Admin/Auditor，查询与导出审计。

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
