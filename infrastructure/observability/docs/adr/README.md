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
