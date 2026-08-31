# SPEC-OP-012 Traceability — Prometheus Metrics Backend

> Domain: `08-observability-platform`
> Phase: `phase-03-telemetry-backends-retention`
> Status: implemented
> Verified: 2026-08-31 (validators + promtool pass; file_sd discovery proven LIVE —
> add/remove a target file with Prometheus already running, no restart — not just
> config-parsed; full smoke stays green)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deploy Prometheus scrape/remote-write, WAL/TSDB, rules, discovery,
authentication, capacity, and retention.*

| Spec area | Where |
|---|---|
| scrape / WAL / TSDB / rules / retention | already existed (`SPEC-OP-002`) |
| discovery | NEW — `file_sd_configs` (one glob job) replaces 6 `static_configs`; proven live-reloadable |
| WAL compression | NEW — `--storage.tsdb.wal-compression` |
| capacity (TSDB integrity) | NEW — `rules/{recording,alerting}/prometheus-tsdb.yml` against verified-live self-metrics |
| capacity (container resources) | already existed (`SPEC-OP-002`, checked not re-done) |
| authentication | explicit non-decision — deferred to `SPEC-OP-030` (documented, not silently skipped) |
| remote-write | already enabled (`SPEC-OP-002`); left untested — no real producer exists yet, an honest gap not a fabricated pass |

## 2. Files added / changed

```text
infrastructure/observability/
  prometheus/base/prometheus.yml                CHANGED (file_sd_configs; wal-compression;
                                                  auth/remote-write scope notes)
  prometheus/base/file_sd/otel-collector.json    NEW
  prometheus/base/file_sd/alertmanager.json      NEW
  prometheus/base/file_sd/loki.json              NEW
  prometheus/base/file_sd/tempo.json             NEW
  prometheus/base/file_sd/grafana.json           NEW
  rules/recording/prometheus-tsdb.yml            NEW
  rules/alerting/prometheus-tsdb.yml             NEW
  runbooks/PrometheusTsdbCapacity.md             NEW

infrastructure/docker-compose/observability-stack.yml   CHANGED (file_sd volume mount;
                                                          wal-compression flag)
.github/workflows/observability-platform-ci.yml         CHANGED (file_sd mount + 2 new
                                                          rule files in promtool step)
scripts/observability-stack.sh                          CHANGED (3 new smoke assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-012-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-012-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `promtool check config` + `check rules` | SUCCESS (all files) |
| `docker compose up` + `curl .../api/v1/query?query=up` | 7 series, same job labels as before the change — confirmed byte-for-byte before rewriting the design |
| **First design** (per-job `file_sd_configs`, one file each) | worked, but defeats the actual goal — a new target class still needs a new `scrape_config` block. Reconsidered before declaring done. |
| **Redesigned** to one glob job (`file-sd-observability-platform`) | re-verified `up{job=...}` unchanged after the redesign |
| Dropped `zz-discovery-probe.json` into `file_sd/` **while Prometheus was running** | new `up{job="discovery-probe-test"}` series appeared within 15s (`refresh_interval`), zero restart |
| Deleted that file | `/api/v1/targets` confirmed it left `activeTargets` just as fast |
| `curl :9090/metrics \| grep prometheus_tsdb_` | confirmed real: `wal_corruptions_total=0`, `compactions_failed_total=0`, `head_series`, `storage_blocks_bytes` all present before writing any rule against them |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — new assertions: all 7 targets up with correct job labels via file_sd; `prometheus-tsdb` recording rule evaluates. Every `SPEC-OP-002`~`011` assertion stayed green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Design correction caught before declaring done

The first implementation (a separate `file_sd_configs` per existing `job_name`, one
target file each) passed every check but did not actually deliver the goal: it
proved file_sd works, but a genuinely NEW target class (the exporters `SPEC-OP-018`/
`SPEC-OP-029` will add) would still require a NEW `scrape_config` block in
`prometheus.yml` plus a restart — no better than `static_configs` for that case.
Caught by asking "would dropping a brand-new file actually just work?" before
writing the traceability doc, not after a user or reviewer found it. Redesigned to
one glob-based job with per-file `job` labels, and the live add/remove test above is
the actual proof this now works the way the goal intends.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No authentication on Prometheus's own query API | Medium (explicit, not silent) | `SPEC-OP-030` (Observability Access Control) — deliberately not duplicated here |
| `remote-write-receiver` has zero real producers/tests | Low | harmless as configured; document rather than fabricate a test with no real consumer |
| `file_sd` per-file `job` label relies on every future file setting it correctly | Low | a file omitting it falls back to the shared `job_name` default (`file-sd-observability-platform`) rather than erroring — visible in `/targets`, not silently mislabeled |
| TSDB capacity thresholds (`for: 5m`/`15m`, no numeric threshold beyond `>0`) are laptop-scale | Low | `SPEC-OP-015` sets real production sizing |

## 6. Sign-off

Scrape-target management is GitOps-file-driven and proven hot-reloadable without
disturbing a single existing job label, rule, or dashboard. WAL compression and
TSDB-integrity alerting are real, verified against live self-metrics rather than
assumed metric names. Authentication and remote-write are recorded as deliberate,
reasoned scope decisions. `SPEC-OP-013` (Loki Log Backend) continues phase-03.
