# Domain Rules — SPEC-OP-036

> Domain: 08-observability-platform
> Phase: `phase-09-final-verification-release`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：汇总 36 spec coverage、版本清单、容量/安全/恢复证据、残余风险、升级回滚和发布签字。

- Signals are immutable observations and preserve source-domain ownership.
- Configuration is versioned per environment and changed through review.
- Business availability takes priority over telemetry delivery; failure is bounded and measured.
- Every artifact declares owner, version, access policy, retention, runbook, rollback and audit reference.
- Tokens, credentials, raw prompts/user text and unredacted PII are forbidden.
