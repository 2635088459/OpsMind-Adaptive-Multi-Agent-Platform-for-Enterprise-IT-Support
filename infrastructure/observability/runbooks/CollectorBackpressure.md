# CollectorBackpressure

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-011
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; recreate otel-collector / prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-011-traceability.md

Covers `CollectorExportFailing`, `CollectorQueueNearCapacity`, and
`CollectorReceiverThrottling` (`rules/alerting/collector-resilience.yml`).

## Impact

**Observability only, up to a point.** The Collector is designed to absorb a backend
outage without dropping data (persistent `sending_queue` + `retry_on_failure`,
`SPEC-OP-011`) and without blocking producers (`memory_limiter` refuses new data
rather than let the process OOM — ADR-0004: business availability outranks
telemetry). But every buffer is finite: sustained pressure past `queue_size` /
`max_elapsed_time` becomes real telemetry loss.

## Detection

- `otelcol:exporter_send_failed_spans:rate5m` / `otelcol:exporter_queue_utilization:ratio`
  / `otelcol:receiver_refused_spans:rate5m` (`rules/recording/collector-resilience.yml`).
- Dashboard: Observability Platform Self-Monitoring.
- Collector logs: `internal/retry_sender.go: Exporting failed. Will retry...`.

## Triage

1. **`CollectorExportFailing`** — which exporter (`otlp/tempo` or `otlphttp/loki`)?
   Check that backend's own health (`docker compose ps`, its own `/ready` or health
   endpoint). This is almost always the downstream backend being down or unreachable,
   not the Collector itself.
2. **`CollectorQueueNearCapacity`** — same first question (which exporter), then: is
   the backend merely SLOW (not fully down) — check its own latency/saturation — or
   is producer volume itself unusually high (check `otelcol_receiver_accepted_spans`
   rate)?
3. **`CollectorReceiverThrottling`** — check `otelcol_process_memory_rss` against the
   configured `memory_limiter` percentage. Either genuine volume growth (check
   accepted-spans rate) or a memory leak/regression in a processor.

## Mitigation

- **Backend down**: restore the backend. The persistent queue (file-storage backed,
  survives a Collector restart too) is already buffering — nothing further to do
  before `max_elapsed_time` (120s per item) elapses. `queue_size` (1000, local 400)
  bounds total buffered items.
- **Backend slow**: same triage as above; if durably slower, the local overlay's
  `queue_size`/`num_consumers` may need raising (base/floor only ever raised, never
  lowered by an overlay).
- **Memory pressure**: raise the container's memory limit + `memory_limiter`
  percentage, or reduce producer volume (check for a runaway high-cardinality
  producer via `SPEC-OP-006`'s cardinality alerts first — often the same root cause).
- Never disable `memory_limiter` or widen `queue_size` without bound "to make the
  alert go away" — that turns a visible, bounded backpressure signal into an
  invisible OOM risk.

## Resolution

Backend healthy, queue utilization back under 80%, export failure rate back to zero,
receiver refusals back to zero.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate the
component.

## Escalation

`platform-observability` → whichever backend (Tempo/Loki) owner if the root cause is
that backend's own capacity, not the Collector.

## Post-incident

If this happens repeatedly under normal (non-incident) load, `SPEC-OP-012`
(Prometheus backend) / real production sizing is the fix, not a bigger local
`queue_size`. Record the real load numbers observed here for that spec.
