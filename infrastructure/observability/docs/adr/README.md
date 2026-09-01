# Architecture Decision Records — Observability Platform

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`

ADRs record decisions that constrain every later Observability Platform spec. Each ADR
is immutable once `Accepted`; a reversal is a new ADR that supersedes it.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-otel-collector-sole-ingestion-boundary.md) | OpenTelemetry Collector is the sole application ingestion boundary | Accepted |
| [0002](0002-prometheus-loki-tempo-backends.md) | Prometheus, Loki, and Tempo are the telemetry backends; no custom backend | Accepted |
| [0003](0003-gitops-versioned-config-with-overlays.md) | Configuration is GitOps: version-pinned, reviewed, base + environment overlays | Accepted |
| [0004](0004-observability-never-mutates-business-state.md) | Observability never mutates business state; business availability outranks telemetry | Accepted |
| [0005](0005-thin-control-plane-api-only-when-gitops-insufficient.md) | A thin control-plane API is added only where GitOps cannot express a change safely | Accepted |
| [0006](0006-repository-layout-and-ownership-model.md) | Repository layout and ownership model for `infrastructure/observability/` | Accepted |
| [0007](0007-otlp-gateway-requires-tls-and-bearer-auth.md) | The OTLP gateway requires TLS and bearer-token auth in every environment, including local | Accepted |
| [0008](0008-sdk-level-redaction-contract.md) | SDK-level redaction is a documented producer contract, not domain-08-owned code | Accepted |
| [0009](0009-config-change-approval-and-audit.md) | Configuration change governance is Git review + CI + git-log audit + a proven git-revert rollback — no new control plane | Accepted |
| [0010](0010-outage-recovery-rto-rpo-targets.md) | Outage recovery targets (RTO/RPO) and the real recovery model for this topology | Accepted |
| [0011](0011-cross-domain-traces-split-across-tenants.md) | A real cross-domain trace splits across Tempo tenants under SPEC-OP-031's model — the correlation entry point is per-tenant, not a single omniscient query | Accepted |

## Format

```markdown
# ADR-NNNN: Title

> Status: Proposed | Accepted | Superseded by ADR-XXXX
> Date: YYYY-MM-DD
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context
## Decision
## Consequences
## Alternatives considered
```
