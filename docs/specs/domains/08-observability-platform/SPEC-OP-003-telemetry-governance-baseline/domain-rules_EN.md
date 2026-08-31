# Domain Rules — SPEC-OP-003

> Domain: 08-observability-platform
> Phase: `phase-00-platform-engineering-foundation`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Define signal allow/deny fields, owners, retention classes, cardinality budgets, schema review, and exception workflow.

- Signals are immutable observations and preserve source-domain ownership.
- Configuration is versioned per environment and changed through review.
- Business availability takes priority over telemetry delivery; failure is bounded and measured.
- Every artifact declares owner, version, access policy, retention, runbook, rollback and audit reference.
- Tokens, credentials, raw prompts/user text and unredacted PII are forbidden.
