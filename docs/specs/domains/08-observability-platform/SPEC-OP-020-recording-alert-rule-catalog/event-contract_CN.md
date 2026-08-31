# Signal and Event Contract — SPEC-OP-020

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：以 Git 管理 recording/alert rules，提供 promtool 校验、稳定 label/annotation、owner/severity/runbook。

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
