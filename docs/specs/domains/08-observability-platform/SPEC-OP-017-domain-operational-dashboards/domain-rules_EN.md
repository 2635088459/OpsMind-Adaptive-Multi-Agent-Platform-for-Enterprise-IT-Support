# Domain Rules — SPEC-OP-017

> Domain: 08-observability-platform
> Phase: `phase-04-dashboards-correlation-analysis`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Deliver owner-specific dashboards for domains 01–07 covering lifecycle, errors, backlog, and audit.

- Signals are immutable observations and preserve source-domain ownership.
- Configuration is versioned per environment and changed through review.
- Business availability takes priority over telemetry delivery; failure is bounded and measured.
- Every artifact declares owner, version, access policy, retention, runbook, rollback and audit reference.
- Tokens, credentials, raw prompts/user text and unredacted PII are forbidden.
