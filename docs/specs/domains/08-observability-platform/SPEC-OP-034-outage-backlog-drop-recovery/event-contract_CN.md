# Signal and Event Contract — SPEC-OP-034

> Domain: 08-observability-platform
> Phase: `phase-08-self-monitoring-recovery-degraded-mode`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：演练 Collector/backend/network/disk 故障、cardinality explosion、积压/丢弃，定义业务 fail-open、RTO/RPO 与恢复。

Every signal carries service.name/version, deployment.environment, timestamp and trace/correlation linkage where applicable. HTTP and AMQP use W3C context. Alert notifications carry alertname, severity, owner, environment, startsAt, fingerprint, dashboard and runbook. Schema changes are additive or versioned; secret/PII and high-cardinality attributes are rejected or removed before export.
