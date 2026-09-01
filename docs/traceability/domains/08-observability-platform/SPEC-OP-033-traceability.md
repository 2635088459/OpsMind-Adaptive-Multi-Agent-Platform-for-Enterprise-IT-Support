# SPEC-OP-033 Traceability — Observability Self Monitoring

> Domain: `08-observability-platform`
> Phase: `phase-08-self-monitoring-recovery-degraded-mode` (opens this phase)
> Status: implemented
> Verified: 2026-09-01
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Self-monitor ingestion, drops, queues, storage,
queries, notifications, and synthetic probes."*

| Surface | Where | Scope |
|---|---|---|
| Ingestion / queues | `SPEC-OP-011` collector-resilience rules | Already real, reused |
| Storage | `SPEC-OP-015` retention/compaction rules | Already real, reused |
| Drops (intentional) | New recording rules, dashboard-only | Distinct from SPEC-OP-011's failure alerts — visibility, not an alert |
| Queries | New per-backend recording + alerting rules | Real metric names, verified live |
| Notifications | New Alertmanager delivery-failure alert | Critical — "who watches the watcher" |
| Synthetic probes | New `synthetic-probe` sidecar | Proven live to detect a real injected failure and self-recover |

## 2. What was found already real vs. genuinely missing

A minimal self-monitoring dashboard (`observability-platform-self.json`)
and 2 seed alerts (`TargetDown`, `PrometheusRuleEvaluationFailing`) already
existed since `SPEC-OP-002` — and that alert file's own comment already
said *"SPEC-OP-033 owns the full observability-platform self-monitoring
alert set"*, confirming this spec's real job from the start: extend an
existing seed, not start from zero. `SPEC-OP-011` (ingestion/queue) and
`SPEC-OP-015` (storage/retention) had already built real self-monitoring
for their surfaces — both explicitly reused here, not duplicated.

Checking what remained found four genuine gaps: intentional drops had no
dedicated visibility (only failures were alerted, via `SPEC-OP-011`); no
backend's own *query* path (as opposed to its ingestion/write path) was
monitored at all; Alertmanager's own notification-delivery health had zero
signal (a real "who watches the watcher" blind spot — if paging itself
silently breaks, nothing else in this stack would know); and no synthetic
probe existed anywhere — every prior proof the pipeline works came from a
human running `scripts/observability-stack.sh smoke`, not a continuous,
independent check.

## 3. What was built

- **`rules/recording/platform-self-monitoring.yml`** (6 rules) +
  **`rules/alerting/platform-self-monitoring.yml`** (6 alerts) — every
  metric name verified live against a real running stack before use (§4.1).
  Intentional drops (`otelcol:filter_dropped_spans:rate5m`,
  `otelcol:tail_sampling_dropped_traces:rate5m`) are recorded for dashboard
  visibility only — deliberately **not** alerted on, since dropping by
  design is not a failure (that distinction is the whole reason this is a
  separate concern from `SPEC-OP-011`'s failure alerts).
- **`runbooks/PlatformSelfMonitoring.md`** — full 8-section structure
  (`Impact`/`Detection`/`Triage`/`Mitigation`/`Resolution`/`Rollback`/
  `Escalation`/`Post-incident`), required since 2 of the new alerts
  (`AlertmanagerNotificationsFailing`, `SyntheticProbeFailing`) are
  `severity: critical`.
- **4 new dashboard panels** on `observability-platform-self.json`: drops,
  per-backend query-error ratio, notification-failure rate, synthetic-probe
  success/duration.
- **`infrastructure/observability/synthetic-probe/`** (new) — a minimal,
  pure-standard-library Python sidecar. Not a custom telemetry backend
  (`ADR-0002` unaffected): it is a **producer** using the exact same real
  OTLP/HTTPS ingestion boundary every other signal in this stack uses
  (`ADR-0007`), plus a Tempo **query-API consumer** — it never talks to a
  storage backend directly for writes. Every 60s (default): pushes a real
  trace through the real Collector, waits past `tail_sampling`'s own
  `decision_wait`, queries Tempo for it, and exposes
  `synthetic_probe_last_success`/`_last_duration_seconds`/`_runs_total`/
  `_failures_total` on its own `/metrics` — scraped via a new
  `file_sd/synthetic-probe.json` entry, no Prometheus config edit needed
  (`SPEC-OP-012`'s existing glob discovery already covers it).

## 4. Real evidence gathered live

### 4.1 Every new metric name verified against a running stack first

Before writing a single rule, curl'd each component's own `/metrics` to
confirm the exact real metric existed (not assumed from documentation):
`otelcol_processor_filter_spans_filtered`,
`otelcol_processor_tail_sampling_global_count_traces_sampled{sampled="false"}`,
`alertmanager_notifications_failed_total{integration,reason}`,
`prometheus_http_requests_total{handler=~"/api/v1/query.*"}`,
`loki_request_duration_seconds_count{route="loki_api_v1_query_range"}`,
`tempo_request_duration_seconds_count{route=~"api_traces_traceid|querier_api_traces_traceid"}`.
All 6 real recording + 6 alerting rules also pass `promtool check rules`.

### 4.2 The synthetic probe genuinely detects failure and self-recovers

Not merely asserted — executed against the running stack:

```text
1. Stack healthy, probe running.
2. curl localhost:9464/metrics -> synthetic_probe_last_success 1
   (a real push through https://otel-collector:4318 + a real Tempo query
   both succeeded, ~13.7s round trip — mostly the tail_sampling wait)
3. docker stop opsmind-tempo
4. Next probe cycle (60s later): docker logs shows
   "run failed: <urlopen error [Errno -2] Name or service not known>"
   -> synthetic_probe_last_success flips to 0, failures_total increments
5. docker start opsmind-tempo; waited for it healthy again
6. Next probe cycle: "run complete ok=True" ->
   synthetic_probe_last_success back to 1, with ZERO manual intervention
   to the probe itself
```

`up{job="synthetic-probe"}` confirmed `1` in Prometheus throughout,
proving the scrape wiring itself never depended on Tempo being up (the
probe process and its `/metrics` endpoint are independent of whether its
own probe *cycle* is currently succeeding).

### 4.3 Full validator + test + smoke sweep

- `validate-observability-layout.py` 0 err/0 warn (transient `audit_ref`
  warnings for the 3 new files resolved once this doc existed).
- `validate-telemetry-governance.py`, `validate-signal-contracts.py`,
  `validate-collector-pipeline.py`, `validate-config-change-audit.py` — all
  0 err/0 warn, unaffected.
- `validate-dashboards.py` 0 err/1 pre-existing warn (unrelated).
- `validate-rule-catalog.py` 0 err/12 warn — **no new orphaned-runbook
  warning**: `PlatformSelfMonitoring.md` is genuinely referenced by all 6
  new alerts. (One real fix made getting here: the first draft of
  `LokiQueryErrorRateHigh`/`TempoQueryErrorRateHigh` was missing
  `annotations.description` — caught by this exact validator, fixed.)
- `scripts/tests/` — 88 passed, unchanged (no validator logic touched by
  this spec).
- `scripts/observability-stack.sh smoke` — **SMOKE: PASS**, every
  `SPEC-OP-002`~`031` assertion green, plus new assertions: all 5 new
  recording rules query-valid, all 6 new alerts loaded,
  `synthetic_probe_last_success=1` confirmed both directly and via
  Prometheus, `job=synthetic-probe` added to the file_sd target-health
  check.
- Full stack (including a fresh `docker build` of the new synthetic-probe
  image) brought up via `up --wait`, every container healthy. Stack torn
  down clean.

## 5. Residual risks / honest limitations

| Risk | Severity | Mitigation / owner |
|---|---|---|
| The synthetic probe's image (`python:3.12-slim`) is not digest-pinned like the 6 real telemetry-backend components `ADR-0003` targets | Low | it is a local utility sidecar, not one of the pinned backends; a future spec could add it to `versions.env` if this asymmetry becomes a real problem |
| The probe only exercises the TRACES path (push a span, query Tempo) | Low | logs/metrics ingestion is already covered by the existing smoke suite at CI/manual time; a continuous logs/metrics probe would be a reasonable future extension, not built here to keep scope proportionate |
| Query-error alert thresholds (5% over 5m) are a reasonable first value, not derived from real production traffic patterns (none exist yet) | Low | same class of honest limitation as every SLO threshold in this domain to date; revisit once real traffic history exists |

## 6. Sign-off

All 7 named self-monitoring surfaces are now covered: ingestion/queues and
storage by reusing real, already-built `SPEC-OP-011`/`SPEC-OP-015` work;
drops, queries, and notifications by new rules verified against real,
live-confirmed metric names; and synthetic probes by a new, minimal,
non-custom-backend sidecar proven — not merely asserted — to detect a real
injected pipeline failure and self-recover once it clears. This opens
`phase-08-self-monitoring-recovery-degraded-mode`.
