# Signal and Event Contract — SPEC-OP-031

> Domain: 08-observability-platform
> Phase: `phase-07-security-privacy-config-governance`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Redact at SDK and Collector, isolate tenant query/retention/encryption, and scan for PII/secrets.

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
