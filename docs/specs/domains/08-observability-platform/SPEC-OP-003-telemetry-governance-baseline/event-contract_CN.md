# Signal and Event Contract — SPEC-OP-003

> Domain: 08-observability-platform
> Phase: `phase-00-platform-engineering-foundation`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：定义 signal allow/deny 字段、owner、retention class、cardinality budget、schema review 和例外流程。

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
