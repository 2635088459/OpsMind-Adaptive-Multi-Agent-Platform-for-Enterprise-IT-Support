# PlatformSelfMonitoring

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-033
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; promtool check rules; recreate prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-033-traceability.md

Covers `PrometheusQueryErrorRateHigh`, `LokiQueryErrorRateHigh`,
`TempoQueryErrorRateHigh`, `AlertmanagerNotificationsFailing`,
`SyntheticProbeFailing`, and `SyntheticProbeStale`
(`rules/alerting/platform-self-monitoring.yml`) — the query-path,
notification-delivery, and synthetic-probe half of this domain's
self-monitoring; `CollectorBackpressure.md` covers the ingestion/queue half
(`SPEC-OP-011`) and `TelemetryBackupRestore.md` covers storage/retention
(`SPEC-OP-015`).

## Impact

**Observability only.** None of these alerts touch a business request path
(`ADR-0004`) — but two are `critical`/paging, not `warning`, because they
each represent a "who watches the watcher" failure mode: if Alertmanager
itself cannot deliver, or the synthetic probe shows the pipeline is broken
end-to-end, no other alert in this stack can be trusted to have fired
correctly either.

## Detection

- Firing expressions:
  - `prometheus:query_errors:ratio5m > 0.05` (5m)
  - `loki:query_errors:ratio5m > 0.05` (5m)
  - `tempo:query_errors:ratio5m > 0.05` (5m)
  - `sum(alertmanager:notifications_failed:rate5m) > 0` (5m)
  - `synthetic_probe_last_success == 0` (5m)
  - `time() - synthetic_probe_last_run_unix > 300` (2m)
- Dashboard: `dashboards/observability-platform-self.json` ("Observability
  Platform Self-Monitoring")
- Correlation entry point: Explore on Prometheus for the recording-rule
  series above; `docker logs opsmind-synthetic-probe` for the probe's own
  per-run pass/fail log line.

## Triage

1. **Which alert fired?** Query vs. notification vs. synthetic-probe point
   at different real components — check the alert name and its `namespace`
   label first.
2. **Query-error alerts** (`Prometheus/Loki/TempoQueryErrorRateHigh`): check
   that backend's own container logs for the real 5xx cause — resource
   pressure (CPU/memory saturation), a malformed query from a dashboard/rule,
   or the backend's own storage layer erroring underneath the query path.
3. **`AlertmanagerNotificationsFailing`**: check
   `alertmanager_notifications_failed_total{reason=...}` — `clientError`
   usually means bad receiver config (URL/credentials), `serverError`/
   `contextDeadlineExceeded` usually means the downstream receiver
   (Slack/PagerDuty/etc.) itself is unreachable or slow.
4. **`SyntheticProbeFailing`**: this means the FULL pipeline is broken even
   if every component reports healthy individually — check the Collector's
   own `otelcol_exporter_sent_spans`/`otelcol_exporter_send_failed_spans`
   for the tenant the probe uses (`observability-platform`), then Tempo's
   own multitenancy/ingestion-limit config (see `tempo/base/overrides.yaml`'s
   own comment on a real, previously-found struct-replace quirk — `SPEC-OP-031`).
5. **`SyntheticProbeStale`**: the probe container itself is down or cannot
   reach Prometheus — check `docker ps`/`docker logs opsmind-synthetic-probe`
   before assuming a pipeline problem.

## Mitigation

Fast actions to stop the bleeding, all observability-only (no business
domain is ever touched by any of these):

- A single backend's query errors: restart just that container
  (`docker compose ... up -d --force-recreate <prometheus|loki|tempo>`) if
  it looks resource-starved or wedged; otherwise let it recover on its own —
  query-path errors do not lose already-ingested data.
- Alertmanager notification failures: if a specific receiver integration is
  down, `alertmanager/base/alertmanager.yml`'s routing tree can be
  temporarily adjusted to route around it (e.g. to a working secondary
  receiver) — a config change following the normal Git-review path
  (`ADR-0009`), not a live edit.
- Synthetic-probe failure: this is a symptom, not a thing to mitigate
  directly — fix the real underlying pipeline break it's surfacing (see
  Triage step 4), then confirm `synthetic_probe_last_success` returns to `1`
  on its own within one probe interval (60s default).

## Resolution

Durable fix depends entirely on the real root cause found in Triage — there
is no single resolution for "self-monitoring alert fired." Once the
underlying backend/notification/pipeline issue is fixed, these alerts
self-clear (Prometheus re-evaluates every 30s) with no manual reset needed.

## Rollback

If a recent config change caused this (most likely for query-path or
notification alerts — e.g. a bad Alertmanager route, a bad recording rule):
`git revert <sha>` the change and redeploy, per `ADR-0009` /
`runbooks/ConfigurationChangeRollback.md`'s own proven procedure.

## Escalation

`platform-observability` (owner) → if unresolved after 30 minutes and
`AlertmanagerNotificationsFailing` is the one firing (meaning paging itself
may not be reaching anyone), escalate out-of-band (direct message/phone),
not through Alertmanager.

## Post-incident

Link this incident's traceability entry once resolved; note which specific
backend/receiver/pipeline stage was the real cause and whether a new
recording rule or dashboard panel would have caught it sooner. See
`docs/traceability/domains/08-observability-platform/SPEC-OP-033-traceability.md`
for this spec's own real verification evidence, including the live
end-to-end proof that the synthetic probe genuinely detects a real pipeline
break (not just a simulated one).
