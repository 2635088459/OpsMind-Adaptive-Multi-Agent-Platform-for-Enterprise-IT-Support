# TelemetryBackupRestore

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-015
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: n/a (this runbook IS the rollback procedure for data loss)
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-015-traceability.md

Covers `LokiRetentionNotRunning`, `TempoRetentionErrors`, and `TempoCompactionErrors`
(`rules/alerting/telemetry-retention.yml`), plus the backup/restore procedure for all
three backends. Every step below was executed for real against a running local
stack, not written from documentation alone — see the traceability doc for the exact
commands and their output.

## Impact

**Observability only** for the alerts (a retention/compaction failure degrades this
platform's own storage, not domains 01–07). Backup/restore is a genuine
disaster-recovery concern: telemetry stores are disposable per F6
(`forbidden-business-writes.md`) — losing them must never lose *business* data — but
losing weeks of dashboards/alert history/trace context still has real operational
cost.

## Backup procedure (proven, per backend)

All three backends use **local filesystem storage** today (ADR-0002; object-store
backends are a production-topology concern). Prometheus additionally has a
consistent, application-level snapshot API — prefer it over a raw filesystem copy.

### Prometheus (snapshot API — preferred)

```sh
# 1. Take a consistent snapshot (requires --web.enable-admin-api, SPEC-OP-015)
curl -X POST http://localhost:9090/api/v1/admin/tsdb/snapshot
# -> {"status":"success","data":{"name":"<snapshot-dir-name>"}}

# 2. Copy the snapshot directory out of the container/volume
docker run --rm -v opsmind-observability_prometheus-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/prometheus-snapshot.tar.gz -C /data/snapshots/<snapshot-dir-name> .
```

### Loki / Tempo (filesystem tar — proven by an actual backup→wipe→restore round trip)

```sh
# Stop the component first for a consistent copy (no snapshot API on local storage).
docker compose -f infrastructure/docker-compose/observability-stack.yml stop loki
docker run --rm -v opsmind-observability_loki-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/loki-backup.tar.gz -C /data .
docker compose -f infrastructure/docker-compose/observability-stack.yml start loki
```

Same pattern for `tempo` (`opsmind-observability_tempo-data`, `/var/tempo`).

## Restore procedure (proven)

```sh
docker compose -f infrastructure/docker-compose/observability-stack.yml rm -sf loki
docker volume rm opsmind-observability_loki-data
docker volume create opsmind-observability_loki-data
docker run --rm -v opsmind-observability_loki-data:/data -v "$PWD":/backup alpine \
  sh -c "tar xzf /backup/loki-backup.tar.gz -C /data"
docker compose -f infrastructure/docker-compose/observability-stack.yml up -d loki
```

**Proof, not assumption**: this exact sequence was run against a live Loki with a
canary log line already ingested — the volume was fully removed (container `rm`, not
just `stop`, is required or Docker refuses to remove an in-use volume), recreated
empty, restored from the tarball, and the canary log line was confirmed still
queryable afterward. Prometheus restore is the mirror image (untar the snapshot into
a fresh `prometheus-data` volume before starting).

## Detection

Retention/compaction health, specifically (backup/restore itself has no alert —
see Impact):

- `loki:compactor_retention_run_age:seconds` / `tempo:retention_errors:rate30m` /
  `tempo:compaction_errors:rate30m` (`rules/recording/telemetry-retention.yml`).
- Prometheus's own equivalents: `prometheus:tsdb_compactions_failed:rate30m`
  (`SPEC-OP-012`, `rules/recording/prometheus-tsdb.yml`).

## Triage

1. **`LokiRetentionNotRunning`**: check `loki_compactor_running` (is the compactor
   module even active in this single-binary deployment?) and Loki's own logs for the
   underlying error.
2. **`TempoRetentionErrors`/`TempoCompactionErrors`**: check disk space first
   (`docker exec opsmind-tempo df -h /var/tempo`), then Tempo's logs for the
   specific error.

## Mitigation

- Disk pressure: free space / grow the volume. Do not raise retention windows as a
  first reaction without understanding why usage grew.
- Persistent compactor/retention failure: restart the component; if it recurs,
  escalate — this is the exact mechanism that keeps disk usage bounded, and a
  sustained failure here is a countdown to a full disk (which then takes every other
  backend's writes down with it).

## Resolution

All three retention-evidence recording rules back to their healthy baseline
(`loki:compactor_retention_run_age:seconds` recently reset; Tempo error rates at
`0`).

## Rollback

This runbook's restore procedure above IS the rollback path for a real data-loss
event. For a rule/config change: `git revert`; `promtool check rules`; recreate the
component.

## Escalation

`platform-observability`. `LokiRetentionNotRunning` / `TempoRetentionErrors` are
`critical` — unbounded disk growth eventually takes the whole platform down.

## Post-incident

If disk pressure was the root cause, record the actual growth numbers here — this
is the spec (`SPEC-OP-015`) that sets real production retention/storage sizing when
a production topology is deployed.
