# Signal and Event Contract — SPEC-OP-036

> Domain: 08-observability-platform
> Phase: `phase-09-final-verification-release`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Compile coverage, BOM, capacity/security/recovery evidence, risks, upgrade/rollback, and sign-off.

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
