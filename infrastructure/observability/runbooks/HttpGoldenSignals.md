# HttpGoldenSignals

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-020
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; promtool check rules; recreate prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-020-traceability.md

Covers `HighRequestErrorRate` and `HighRequestLatency`
(`rules/alerting/http-server.yml`) — the HTTP golden signals (rate/errors/duration)
for the **business request path**, distinct from every earlier spec's own
platform-self-monitoring alerts (Collector/Prometheus/Loki/Tempo internals).

## Impact

**Real business impact** — unlike most alerts in this domain. A sustained 5xx ratio
or p95 latency spike here means real requests are failing or slow for real users,
in whichever domain (01-07) emitted the metric (`service_namespace` label).

## Detection

- `http:error_ratio:rate5m` / `http:duration_p95:5m`
  (`rules/recording/http-server.yml`).
- Golden Path & Service Overview dashboard (`SPEC-OP-016`) — same underlying series.

## Triage

1. Identify `service_name` / `service_namespace` from the alert labels.
2. Open the Golden Path dashboard filtered to that `service_namespace`.
3. **`HighRequestErrorRate`**: check the Saturation row first (CPU/memory pressure
   causing failures is common); then use the Trace drilldown panel (TraceQL) to
   find a representative failing trace and follow it to the actual error.
4. **`HighRequestLatency`**: check Saturation first; if resources look fine, check
   whether a downstream dependency (DB/AMQP panels, `SPEC-OP-018`, or another
   domain's own dashboard, `SPEC-OP-017`) is the actual bottleneck via the trace's
   span waterfall.

## Mitigation

- This is a business-domain issue; domain 08 does not remediate it. Route to the
  owning domain's on-call per `service_namespace` — routing/paging specifics are
  `SPEC-OP-021`.
- Never silence this alert to "make it stop" without either fixing the root cause
  or filing a tracked exception — a silenced golden-signal alert is a blind spot on
  the actual business request path.

## Resolution

`http:error_ratio:rate5m` back under 5%; `http:duration_p95:5m` back under 1s.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate
`prometheus`.

## Escalation

The owning domain's on-call (per `service_namespace`) — `platform-observability`
owns the alert DEFINITION, not the business remediation (ADR-0004).

## Post-incident

If the 5%/1s thresholds are wrong for a specific domain's real traffic pattern,
that is real input for `SPEC-OP-022`'s SLO model (a domain-specific SLO target
supersedes this generic platform-wide floor).
