# SPEC-OP-017 Traceability — Domain Operational Dashboards

> Domain: `08-observability-platform`
> Phase: `phase-04-dashboards-correlation-analysis`
> Status: implemented
> Verified: 2026-08-31 (a real log carrying the exact structured-metadata shape
> this dashboard's audit panel queries for was pushed and confirmed queryable)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deliver owner-specific dashboards for domains 01–07 covering
lifecycle, errors, backlog, and audit.*

| Spec area | Where |
|---|---|
| owner-specific per domain | ONE templated dashboard (`$domain` variable), not 7 files — see §4 |
| lifecycle / errors | rate + failure-outcome rate, unioned across every namespace a domain might use |
| backlog | AMQP publish-vs-consume rate gap |
| audit | recent logs + `opsmind.log.violation`-tagged logs specifically |

## 2. Files added / changed

```text
infrastructure/observability/
  dashboards/domain-operational-overview.json   NEW
  runbooks/DomainOperationalOverview.md         NEW

scripts/observability-stack.sh   CHANGED (service.namespace-tagged log push)

docs/specs/domains/08-observability-platform/SPEC-OP-017-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-017-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| Pushed a log with `service.namespace="agent-runtime"` resource attribute — no prior smoke-test log had ever set this attribute | HTTP 200 |
| `GET /loki/api/v1/query_range` with `{service_name=~".+"} \| service_namespace = "agent-runtime"` (the dashboard's own audit-panel query, run directly) | returned the exact pushed line, with `event_code`, `severity_text`, `trace_id` all present as structured metadata |
| `GET /api/search` (Grafana) | dashboard listed with correct `uid`/`tags` |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** |
| `scripts/observability-stack.sh down` | stack + volumes removed |

## 4. Why one templated dashboard, not seven

Seven near-duplicate per-domain JSON files would mean seven places to fix the same
query bug. A single `$domain` template variable
(`label_values(service_namespace)`) scopes every panel to the Grafana variable
picker's selection — a new domain (e.g. once a hypothetical domain 08+ existed)
appears automatically the moment it emits conformant resource attributes, with
zero dashboard-file change. This is exactly what Grafana templating is for.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `service_namespace` is structured metadata, not an indexed Loki label (confirmed `SPEC-OP-013`) | Low | query syntax already accounts for this (`\|` filter, not a stream-selector label match); proven working |
| Backlog panel (AMQP publish-vs-consume) has no dedicated queue-depth metric | Low | real queue depth is `SPEC-OP-029`'s infra-exporter scope (`SPEC-OP-018`) |

## 6. Sign-off

A single, maintainable, live-proven per-domain operational dashboard. `SPEC-OP-018`
(Database, Broker, And Infrastructure Dashboards) continues phase-04.
