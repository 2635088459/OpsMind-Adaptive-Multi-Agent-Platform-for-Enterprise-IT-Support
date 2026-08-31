# SPEC-OP-015 Traceability — Telemetry Retention, Compaction, And Storage

> Domain: `08-observability-platform`
> Phase: `phase-03-telemetry-backends-retention` (closes this phase)
> Status: implemented
> Verified: 2026-08-31 (a full backup→wipe→restore round trip was actually
> performed on Loki, not simulated; Prometheus's admin snapshot API produced a real
> snapshot on disk; deletion-evidence rules verified against real metric names)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Define capacity, storage, retention, compaction, backup/restore,
and deletion evidence by signal/class.*

| Spec area | Where |
|---|---|
| capacity / storage / retention / compaction (base values) | already existed (`SPEC-OP-002`); this spec did not change any retention duration |
| backup/restore | NEW — real, proven procedure (`runbooks/TelemetryBackupRestore.md`) |
| deletion evidence | NEW — `rules/{recording,alerting}/telemetry-retention.yml` for Loki+Tempo (Prometheus's own is `SPEC-OP-012`) |
| retention **by signal/class** | honest governance-level statement (`retention_mapping`) that no backend enforces this natively today — not silently implied as done |

## 2. Files added / changed

```text
infrastructure/observability/
  governance/telemetry-governance.yaml       CHANGED (v1.5.0: retention_mapping section)
  schemas/telemetry-governance.schema.json   CHANGED (retention_mapping property)
  rules/recording/telemetry-retention.yml    NEW
  rules/alerting/telemetry-retention.yml     NEW
  runbooks/TelemetryBackupRestore.md         NEW

infrastructure/docker-compose/observability-stack.yml   CHANGED (--web.enable-admin-api)
scripts/validate-telemetry-governance.py                CHANGED (retention_mapping checks)
.github/workflows/observability-platform-ci.yml         CHANGED (2 new rule files)
scripts/observability-stack.sh                          CHANGED (2 new smoke assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-015-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-015-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| Pushed a canary log (`BACKUP_RESTORE_CANARY_LOG_LINE_12345`) to Loki | confirmed queryable |
| `POST /api/v1/admin/tsdb/snapshot` | `{"status":"success","data":{"name":"20260831T205310Z-..."}}`; `docker exec ... ls /prometheus/snapshots/` confirmed the directory exists on disk |
| `docker stop opsmind-loki` + tar backup of `loki-data` via a throwaway alpine container | `loki-backup.tar.gz` produced |
| `docker volume rm opsmind-observability_loki-data` (right after `stop`) | **failed**: `volume is in use` |
| `docker rm opsmind-loki` (full container removal, not just stop) then `docker volume rm` | succeeded |
| Recreated the volume empty, restored from the tarball, `docker compose up -d loki` | Loki healthy |
| Queried for `BACKUP_RESTORE_CANARY_LOG_LINE_12345` again | **found** — survived a full volume wipe and restore |
| `uv run --with pyyaml python scripts/validate-telemetry-governance.py` | 0 errors, 0 warnings |
| `promtool check rules` (both new files) | SUCCESS — 5 recording + 3 alerting |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — new assertions: `tempo:retention_deleted:rate1h` recording rule evaluates; Prometheus admin snapshot API responds success. Every `SPEC-OP-002`~`014` assertion in the same run stayed green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. One real Docker mechanic caught mid-test

`docker stop` alone does not release a volume — `docker volume rm` failed with
"volume is in use" even with the container stopped. The container must be
**removed** (`docker rm`), not merely stopped, before its volume can be removed and
recreated. This is now the exact sequence documented in
`runbooks/TelemetryBackupRestore.md`'s restore procedure, learned by hitting the
error, not from prior knowledge.

## 5. The honest retention-by-class finding

`governance/telemetry-governance.yaml` has said "Real production durations are set
by `SPEC-OP-015`" since `SPEC-OP-002`. The real finding closing that reference:
**none of Prometheus, Loki, or Tempo's open-source editions support native
per-signal-class retention tiering** — each has exactly one global retention
duration. Every artifact's `retention: debug` / `retention: standard` / etc.
metadata field is a **declared intent**, not something currently enforced
per-class. Real per-class enforcement needs either promoting a label to enable
Loki's `retention_stream` (a cardinality decision `SPEC-OP-013` already declined to
make as a side effect of ruler wiring, and this spec declines for the identical
reason) or a tiered/object-store backend (out of scope per ADR-0002 absent a real
production need). `retention_mapping` in governance now states this plainly instead
of leaving it implied.

## 6. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No backend enforces true per-signal-class retention | Medium (explicit, not silent) | requires either a label-promotion cardinality review or a tiered backend; deferred with reasons recorded, not silently skipped |
| Backup/restore has no automation (manual commands in a runbook) | Low | appropriate for local topology; a production topology spec would script this |
| `LokiRetentionNotRunning` threshold (`21600s` / 6h) is a laptop-scale guess | Low | tune against real production compaction cadence when deployed |

## 7. Sign-off

Backup and restore are proven end to end against a real volume wipe, not merely
documented. Deletion is now an observable, alertable fact for every backend.
Retention-by-signal-class is stated honestly as not-yet-achievable rather than
implied as done. This closes **phase-03 (Telemetry Backends And Retention,
`SPEC-OP-012`~`015`)** for domain 08. `SPEC-OP-016` (Golden Path / Service Overview
Dashboard) opens phase-04 (Dashboards And Correlation Analysis).
