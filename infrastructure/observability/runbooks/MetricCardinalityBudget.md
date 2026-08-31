# MetricCardinalityBudget

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-006
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; recreate prometheus / otel-collector
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-006-traceability.md

Covers `MetricSeriesBudgetExceeded`, `HighCardinalityJob`, and `ForbiddenMetricLabel`
(`rules/alerting/cardinality.yml`).

## Impact

**Observability only.** High cardinality does not break the business request path, but
it inflates Prometheus memory / disk, slows every query, and can eventually make the
TSDB fall over — taking dashboards and alerting down with it.

## Detection

- `series:count:total` / `prometheus_tsdb_head_series` — global active series.
- `job:series:count` — per scrape job.
- `ForbiddenMetricLabel` — a per-request id label (`ticket_id`, `workflow_id`,
  `user_id`, `run_id`, `correlation_id`, `session_id`) reached Prometheus, i.e. the
  Collector `transform/metric-cardinality` guard did not do its job.

## Triage

1. **Which job / metric?**
   `topk(10, count by (__name__) ({__name__=~".+"}))` — the metric name with the most
   series.
   `count by (__name__, job) ({job="<job>"})` — narrow to a job.
2. **Which label exploded?**
   `count by (<label>) (<metric>)` for each label of the offending metric — the one
   with hundreds/thousands of values is the culprit.
3. Compare against `signals/metric-naming.md` §5 — is that label on the namespace's
   allow-list? Is its value bounded?

## Mitigation

- **`ForbiddenMetricLabel` firing**: confirm `transform/metric-cardinality` is wired
  into the metrics pipeline (`scripts/validate-signal-contracts.py`) and that its
  regex covers the label. If a producer is emitting a brand-new id label not in the
  governance `forbidden_labels` union, add it there + to the Collector regex (one PR)
  and redeploy the Collector.
- **`HighCardinalityJob` / `MetricSeriesBudgetExceeded`**: drop the offending label in
  a producer-side metrics SDK view, or (fast mitigation) add a
  `metric_relabel_configs` `labeldrop` in `prometheus/base/prometheus.yml` for that
  label on that job, then fix the producer.
- Never fix this by raising the budget without understanding the cause.

## Resolution

Producer emits the metric with only allow-listed, bounded labels; series count returns
under budget; alert resolves.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate the
component.

## Escalation

`platform-observability` → the namespace's semantic owner (`signals/metric-naming.yaml`
`namespaces`). `ForbiddenMetricLabel` at `critical` pages if it persists past `for`.

## Post-incident

If a whole class of metrics is affected, tighten the SDK metric view in the shared
bootstrap and record it in the SPEC-OP-025+ cross-domain observability contract.
