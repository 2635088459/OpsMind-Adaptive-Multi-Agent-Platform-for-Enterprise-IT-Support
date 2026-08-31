# TempoIngestHealth

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-014
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; recreate tempo / prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-014-traceability.md

Covers `TempoDiscardingSpans`, `TempoBlockFlushFailing`, and
`TempoMetricsGeneratorDroppingSpans` (`rules/alerting/tempo-health.yml`).

## Impact

**Observability only, with one exception.** Discarded spans and failed block
flushes mean real trace data loss (Tempo is this platform's sole trace backend,
ADR-0002). Metrics-generator drops only affect the span-metrics/service-graph
*views* derived from traces — the traces themselves are unaffected.

## Detection

- `tempo:discarded_spans:rate5m` / `tempo:ingester_failed_flushes:rate5m` /
  `tempo:metrics_generator_spans_discarded:rate5m`
  (`rules/recording/tempo-health.yml`).
- Dashboard: Observability Platform Self-Monitoring.
- Tempo's own logs (`docker compose logs tempo`).

## Triage

1. **`TempoDiscardingSpans`**: check the `reason` label — usually a batch-size limit,
   an `overrides` per-tenant limit, or a malformed OTLP payload. Cross-reference
   `otelcol_exporter_send_failed_spans` (`SPEC-OP-011`) — if the Collector is ALSO
   seeing failures, the root cause is likely on the Tempo side (rejecting, not just
   dropping after acceptance).
2. **`TempoBlockFlushFailing`**: check disk space
   (`docker exec opsmind-tempo df -h /var/tempo`) — the most common cause. Otherwise
   check logs for a specific storage-backend error.
3. **`TempoMetricsGeneratorDroppingSpans`**: check the `reason` label; this does not
   threaten trace storage, only the derived span-metrics/service-graph series.

## Mitigation

- **Discarded spans / limit-related**: raise the relevant `overrides` limit if
  genuine traffic growth (not a runaway producer) is the cause — check
  `SPEC-OP-006`-style cardinality signals first to rule out a misbehaving producer.
- **Flush failures / disk full**: free space or grow the volume. Never raise
  `block_retention` as a first reaction to disk pressure without understanding why
  usage grew.
- **Metrics-generator drops**: usually resolves once the underlying discard reason
  (often the same root cause as span discards) is fixed.

## Resolution

All three recording-rule expressions back to `0`.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate the
component.

## Escalation

`platform-observability`. `TempoBlockFlushFailing` is `critical` — real trace data
loss risk — and pages accordingly.

## Post-incident

If disk pressure was the root cause, `SPEC-OP-015` (Telemetry Retention,
Compaction, And Storage) is where real production sizing/retention gets set —
record the actual growth numbers observed here for that spec.
