# Domain Rules — SPEC-OP-033

> Domain: 08-observability-platform
> Phase: `phase-08-self-monitoring-recovery-degraded-mode`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：监控 accepted/refused/dropped、queue/export、WAL/TSDB、ingestion/compaction、query、notification 与 synthetic probe。

- Signals are immutable observations and preserve source-domain ownership.
- Configuration is versioned per environment and changed through review.
- Business availability takes priority over telemetry delivery; failure is bounded and measured.
- Every artifact declares owner, version, access policy, retention, runbook, rollback and audit reference.
- Tokens, credentials, raw prompts/user text and unredacted PII are forbidden.
