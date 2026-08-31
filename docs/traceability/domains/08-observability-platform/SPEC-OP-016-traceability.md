# SPEC-OP-016 Traceability — Golden Path And Service Overview Dashboard

> Domain: `08-observability-platform`
> Phase: `phase-04-dashboards-correlation-analysis` (opens this phase)
> Status: implemented
> Verified: 2026-08-31 (Grafana's own `/api/search` confirmed provisioning; key
> panel queries verified against real pushed data, not just JSON-parsed)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deliver RED/saturation, Golden Path stages, trace drilldown, and
deployed-version overview.*

| Spec area | Where |
|---|---|
| RED (rate/errors/duration) | timeseries panels against `http.*` namespace, templated by `$service_namespace` |
| Saturation | CPU/memory via `runtime.*` namespace |
| Golden Path stages | agent run duration, tool-call rate, evaluation case duration |
| Trace drilldown | native Grafana `traces` panel type, Tempo datasource, TraceQL |
| Deployed-version overview | table panel using `resource_to_telemetry_conversion`-derived labels |
| dashboard-JSON validation | NEW `scripts/validate-dashboards.py` — a gap left open since `SPEC-OP-002` |

## 2. Files added / changed

```text
infrastructure/observability/
  dashboards/golden-path-service-overview.json   NEW
  runbooks/GoldenPathServiceOverview.md          NEW

scripts/validate-dashboards.py                    NEW
scripts/tests/test_validate_dashboards.py         NEW
.github/workflows/observability-platform-ci.yml   CHANGED (new layout-job step)
scripts/observability-stack.sh                    CHANGED (dashboard-data metrics push)

docs/specs/domains/08-observability-platform/SPEC-OP-016-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-016-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `uv run --with pyyaml python scripts/validate-dashboards.py` | 0 errors (against the real dashboard tree) |
| `uv run --with pyyaml python -m unittest ...` (writing the validator's own tests) | first draft crashed: `path.relative_to(REPO)` raised `ValueError` when called with a tempdir path outside the repo — fixed with a try/except fallback |
| Pushed `http_server_request_duration_seconds` (200 + 500), `agent_run_duration_seconds`, `agent_tool_calls_total`, `runtime_cpu_utilization_ratio` with real `service.version`/`service.namespace` resource attributes | HTTP 200, `{"partialSuccess":{}}` |
| `GET /api/search?type=dash-db` (Grafana, `admin:admin`) | lists all 5 dashboards (4 new + the pre-existing self-monitoring one) with correct `uid`/`title`/`tags` |
| `GET /api/v1/query?query=count by (service_name, service_namespace, service_version, deployment_environment) ({__name__=~"..."})` | returned exactly the 2 pushed services with correct labels — the deployed-versions panel's own query |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — every `SPEC-OP-002`~`015` assertion in the same run stayed green |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Honest limit on "proven"

The RED/saturation/Golden-Path panels query the canonical contract names
(`signals/metric-naming.yaml`) — correct and ready — but no real domain 01-07
producer emits them yet. This session's own smoke traffic is
contract-compliant **synthetic** data, proving the query/label/datasource wiring
is correct, not that a real production workload is flowing through it. That proof
arrives naturally once real producers adopt the contract.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No real producer traffic yet | Low (expected at this stage) | proof completes itself as domains 01-07 adopt the metric-naming contract |
| Thresholds (error-ratio yellow/red at 1%/5%) are placeholder judgment calls | Low | tune once real traffic patterns are known |
| Trace-drilldown panel's default TraceQL query (`{}`) returns everything, unfiltered | Low | intentional — a live search box, not a fixed query |

## 6. Sign-off

A real, Grafana-provisioned dashboard proven against actual pushed data, plus a
dashboard-JSON validator this domain had promised since `SPEC-OP-002` and never
built. `SPEC-OP-017` (Domain Operational Dashboards) continues phase-04.
