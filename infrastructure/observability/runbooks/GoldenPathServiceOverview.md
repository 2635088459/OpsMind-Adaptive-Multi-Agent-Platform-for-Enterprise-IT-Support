# GoldenPathServiceOverview

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-016
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: n/a (view only)
> runbook: self
> rollback: git revert <sha>; re-run grafana provisioning
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-016-traceability.md

Companion runbook for the **Golden Path & Service Overview** dashboard
(`dashboards/golden-path-service-overview.json`). This is a dashboard-usage guide,
not an alert runbook — the RED/saturation panels here are read alongside the actual
alerting rules (`SPEC-OP-020`+) that page on them.

## What this dashboard shows

- **RED** (Rate / Errors / Duration) for the HTTP ingress layer, using the
  canonical `http.*` namespace (`SPEC-OP-006`): request rate, 5xx error ratio, p95
  duration — templated by `$service_namespace` so any domain's own producer shows
  up automatically once it emits conformant metrics.
- **Saturation**: CPU/memory via the `runtime.*` namespace.
- **Golden Path stages**: agent run duration, tool-call rate, evaluation case
  duration — the ticket → agent → tool → evaluation path across domains 02/03/05/07.
- **Trace drilldown**: a live TraceQL search panel (Tempo) for jumping from a
  metric spike straight into individual traces.
- **Deployed versions**: which `service.version` each `service.name` is currently
  running, via `resource_to_telemetry_conversion` (every OTLP resource attribute
  becomes a metric label — `SPEC-OP-002`).

## How to read it

1. Start at the RED row. A rate drop with a stable error ratio is usually
   upstream traffic, not this service. A rising error ratio with stable rate is
   this service degrading.
2. Cross-check Saturation — if CPU/memory is climbing with the error ratio, it is
   probably resource-bound (`memory_limiter`/CollectorBackpressure territory,
   `SPEC-OP-011`).
3. Use the Golden Path row to localize WHICH stage of the pipeline the problem is
   in before jumping to a domain-specific dashboard (`SPEC-OP-017`).
4. Click a datapoint's exemplar (or use the Trace drilldown panel directly) to jump
   into the actual trace for that time window.

## Data provenance

Every panel here queries the metric-naming contract's canonical names
(`signals/metric-naming.yaml`) — real production series appear once domains 01-07
emit them; verified for THIS spec against synthetic-but-contract-compliant metrics
pushed by `scripts/observability-stack.sh smoke` (see the traceability doc for
exactly which panels were live-verified vs. contract-correct-but-not-yet-exercised
by a real producer).

## Rollback

`git revert` the dashboard/runbook change; re-run Grafana provisioning
(`docker compose up -d --force-recreate grafana`, or wait for the file provider's
30s poll).
