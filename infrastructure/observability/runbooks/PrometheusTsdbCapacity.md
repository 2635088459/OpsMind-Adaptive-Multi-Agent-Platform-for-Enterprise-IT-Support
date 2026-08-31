# PrometheusTsdbCapacity

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-012
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; recreate prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-012-traceability.md

Covers `PrometheusTsdbWalCorruption` and `PrometheusTsdbCompactionsFailing`
(`rules/alerting/prometheus-tsdb.yml`).

## Impact

**Observability only, but severe.** Prometheus is this platform's sole metrics
backend (ADR-0002 — no custom backend). A WAL corruption or persistent compaction
failure threatens the platform's ability to observe every other domain, and can mean
real metric-history data loss, not just a missed alert.

## Detection

- `prometheus:tsdb_wal_corruptions:rate30m` / `prometheus:tsdb_compactions_failed:rate30m`
  (`rules/recording/prometheus-tsdb.yml`).
- Prometheus's own logs (`docker compose logs prometheus`) at the time of the alert.
- `prometheus_tsdb_head_series` (SPEC-OP-006's cardinality dashboard) — rule out a
  cardinality blowup as an indirect cause of compaction pressure first.

## Triage

1. **`PrometheusTsdbWalCorruption`**: check the container logs for the exact
   corrupted segment. Prometheus auto-truncates a corrupt WAL tail on restart — data
   in that window is lost, but the process recovers. Confirm disk isn't full and the
   volume isn't experiencing I/O errors (the two common root causes).
2. **`PrometheusTsdbCompactionsFailing`**: check disk space first
   (`docker exec opsmind-prometheus df -h /prometheus`); a nearly-full volume is the
   most common cause. Otherwise check logs for a specific compaction error.

## Mitigation

- **Disk full**: free space or grow the volume; do not lower retention as a first
  reaction without understanding why usage grew (check `SPEC-OP-006` cardinality
  alerts first — a label explosion is often the real cause).
- **Corrupt WAL**: `docker compose restart prometheus` triggers Prometheus's own WAL
  recovery (truncate-and-continue). If corruption recurs, suspect underlying disk
  health, not Prometheus itself.
- **Compaction failing for another reason**: escalate with the exact log line;
  compaction bugs tied to a specific Prometheus version are rare but real — check the
  pinned version's release notes before assuming it is environmental.

## Resolution

`prometheus:tsdb_wal_corruptions:rate30m` and
`prometheus:tsdb_compactions_failed:rate30m` both back to `0`; disk usage stable.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate
`prometheus`.

## Escalation

`platform-observability`. A WAL corruption that recurs across restarts is a
data-integrity incident, not routine noise — treat it accordingly.

## Post-incident

If disk pressure was the root cause, `SPEC-OP-015` (Telemetry Retention, Compaction,
And Storage) is where real production sizing/retention gets set — record the actual
growth numbers observed here for that spec.
