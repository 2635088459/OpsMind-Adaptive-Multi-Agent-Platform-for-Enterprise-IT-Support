# Signal and Event Contract — SPEC-OP-035

> Domain: 08-observability-platform
> Phase: `phase-09-final-verification-release`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：执行 Identity/MFA 工单跨 01–07/HTTP/AMQP 的完整 trace，验证 dashboard/alert/runbook 与 chaos。

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
