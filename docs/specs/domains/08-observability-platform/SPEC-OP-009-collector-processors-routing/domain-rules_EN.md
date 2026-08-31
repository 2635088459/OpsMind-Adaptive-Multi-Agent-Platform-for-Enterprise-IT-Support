# Domain Rules — SPEC-OP-009

> Domain: 08-observability-platform
> Phase: `phase-02-collector-intake-processing`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Configure and test ordered memory, resource, attribute, transform, filter, routing, and redaction processors.

- Signals are immutable observations and preserve source-domain ownership.
- Configuration is versioned per environment and changed through review.
- Business availability takes priority over telemetry delivery; failure is bounded and measured.
- Every artifact declares owner, version, access policy, retention, runbook, rollback and audit reference.
- Tokens, credentials, raw prompts/user text and unredacted PII are forbidden.
