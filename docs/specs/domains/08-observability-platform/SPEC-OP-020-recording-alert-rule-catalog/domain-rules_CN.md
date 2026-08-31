# Domain Rules — SPEC-OP-020

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：以 Git 管理 recording/alert rules，提供 promtool 校验、稳定 label/annotation、owner/severity/runbook。

- Signals are immutable observations and preserve source-domain ownership.
- Configuration is versioned per environment and changed through review.
- Business availability takes priority over telemetry delivery; failure is bounded and measured.
- Every artifact declares owner, version, access policy, retention, runbook, rollback and audit reference.
- Tokens, credentials, raw prompts/user text and unredacted PII are forbidden.
