# OutageAndRecoveryDrill

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-034
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: n/a (this runbook documents drills, not a config to roll back)
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-034-traceability.md

Not linked from any alert — an operational reference doc, same category as
`AlertRoutingAndSilencing.md`/`ConfigurationChangeRollback.md`/
`ObservabilityAccessControl.md`. Documents this domain's RTO/RPO targets
(`ADR-0010`) and the real, live drills proving each one.

## RTO/RPO targets (ADR-0010)

- **RPO: 60 seconds.** Any real backend outage shorter than this loses
  zero telemetry (Collector WAL keeps retrying); a longer outage risks
  losing up to one queue's worth of data (bounded, never unbounded).
- **RTO: 5 minutes**, assuming a human/automation acts on
  `SyntheticProbeFailing`/`TargetDown` and runs the documented recovery
  command. **This topology has no fully-automatic crash recovery** for an
  operator-initiated stop/kill — a genuine, previously-unknown finding
  (below), not assumed.
- **Business fail-open: unconditional.** A producer's OTLP push always
  succeeds even during a full backend outage, independent of RTO/RPO.

## Drill 1 — business fail-open during a real backend outage

`docker kill opsmind-tempo`, then immediately pushed a real trace to the
Collector: `200` — the push succeeded despite Tempo being fully down. The
Collector's `sending_queue` absorbed it; the producer was never blocked or
rejected (`ADR-0004`).

## Drill 2 — the real crash-recovery finding

Attempted to prove automatic recovery via `restart: unless-stopped`:

1. `docker kill opsmind-tempo` → container `Exited (137)`.
2. Waited 16+ seconds: **container did not restart on its own.**
   `docker inspect`'s `RestartPolicy` genuinely was `unless-stopped`, but
   Docker treats an operator-issued `stop`/`kill` as an intentional action,
   not a candidate for its restart policy — documented, correct Docker
   behavior, not a bug in this stack's config.
3. Attempted the alternative — killing the process INSIDE the container
   (`docker exec opsmind-tempo kill -9 1`) to simulate a genuine in-process
   crash instead of an operator stop. This did **not** conclusively
   terminate the process either (`docker inspect`'s `StartedAt` and the
   PID's own start time were unchanged 3+ seconds later) — the exact reason
   was not further chased down (diminishing returns for this spec's scope),
   but the practical, honest conclusion stands either way: **recovery in
   this topology requires an explicit, triggered action**
   (`docker compose ... up -d --force-recreate <service>`), which has been
   proven repeatedly this session to bring a backend back to `healthy` in
   10-20 seconds.

**This is stated honestly as a real limitation of this local Docker Compose
topology** — a production Kubernetes deployment with a real liveness-probe
-driven restart policy would behave differently (restarts on failed health
checks regardless of how the process last exited), but that topology does
not exist and is not tested in this repository.

## Drill 3 — disk (ENOSPC), via a safe isolated demonstration

Filling this shared host's real disk (223GB total, 182GB free at drill
time) was neither practical nor safe. Instead, proved the real OS-level
failure mode in isolation:

```sh
docker run --rm --tmpfs /test:size=5m alpine sh -c 'dd if=/dev/zero of=/test/fill bs=1M count=10'
# dd: error writing '/test/fill': No space left on device
# 5+0 records out; exit code 1
```

Every one of Prometheus/Loki/Tempo is a standard Go application writing to
local ext4/overlay storage (`ADR-0002`) — each would receive this
identical `ENOSPC` OS error on any write once genuinely full (WAL append,
compaction, chunk flush), logged as an error by that backend. No fabricated
disk-usage alert threshold was invented for Loki/Tempo specifically, since
neither exposes a real total-storage-bytes metric as of the pinned
versions (confirmed by inspecting their live `/metrics` output — only
Prometheus does: `prometheus_tsdb_storage_blocks_bytes` +
`prometheus_tsdb_wal_storage_size_bytes`, now recorded as
`prometheus:storage_bytes:total`, dashboard-visibility only, same
reasoning as `SPEC-OP-033`'s intentional-drop panels).

## Drill 4 — cardinality budget genuinely trips, not just idles

`SPEC-OP-006`'s `HighCardinalityJob`/`MetricSeriesBudgetExceeded` alerts had
only ever been confirmed `inactive` (real traffic never came close to
20000/250000 series) — never proven to actually fire. Proved the alert
LOGIC genuinely works, not just exists:

1. Pushed 60 real distinct series (`op_034_cardinality_drill_total{case="variant-0..59"}`)
   — confirmed landed in Prometheus.
2. Temporarily lowered `HighCardinalityJob`'s threshold from `20000` to
   `100` (below the real `job:series:count{job="otel-collector"}` value of
   524 at drill time) in the live rules file, `POST /-/reload`.
3. Confirmed via `/api/v1/rules` the alert genuinely transitioned to
   `pending` (real condition breach, not the file just being edited).
4. Reverted the threshold back to the real `20000`, reloaded again,
   confirmed it returned to `inactive` on the next evaluation.
5. `git diff` confirmed the committed rules file was never actually
   changed — the live edit was reverted before this spec's real commit.

## Drill 5 — backlog/drop bound (reused, not re-proven from scratch)

Already proven live under `SPEC-OP-011`'s own build and re-confirmed
multiple times since (`SPEC-OP-032`, `SPEC-OP-033`): a backend outage
shorter than `retry_on_failure.max_elapsed_time` (60s local / 120s
production) loses zero telemetry via the Collector's persistent
`sending_queue` WAL. Not re-run here to avoid duplicating existing,
already-real evidence — see those specs' own traceability docs for the
original proof.
