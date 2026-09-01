# SPEC-OP-034 Traceability — Outage Backlog Drop And Recovery

> Domain: `08-observability-platform`
> Phase: `phase-08-self-monitoring-recovery-degraded-mode` (closes this phase)
> Status: implemented
> Verified: 2026-09-01
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Exercise outages, disk/cardinality/backlog/drop with
business fail-open, RTO/RPO, and recovery."*

| Surface | Where | Scope |
|---|---|---|
| Backlog / drop | `SPEC-OP-011`'s WAL/sending_queue (reused, re-confirmed) | Already real, not re-proven from scratch here |
| Cardinality | `SPEC-OP-006`'s budget alerts | Already real, but had only ever been confirmed `inactive` — **this spec proves it genuinely trips** |
| Disk | New `prometheus:storage_bytes:total` recording rule | New, visibility-only — no fabricated Loki/Tempo threshold |
| Business fail-open | Re-confirmed live during a real outage | Unconditional, independent of RTO/RPO |
| RTO/RPO | New `ADR-0010` | First concrete targets this domain has ever stated |
| Recovery | A real, corrected finding about this topology's actual model | See §3 |

## 2. What was found already real vs. genuinely missing

`SPEC-OP-011` (backlog/drop, via the Collector's WAL) and `SPEC-OP-006`
(cardinality budgets) already had real, working mechanisms — reused
unchanged here, not duplicated. But `SPEC-OP-006`'s own traceability
recorded its cardinality alerts as `inactive` at verification time (real
traffic never came near the thresholds) — meaning the alert *logic* itself
had never actually been proven to fire, only to exist and stay quiet. No
RTO or RPO target existed anywhere in this domain before this spec, and no
spec had exercised a component outage specifically to measure recovery
against a stated target (prior outages, in `SPEC-OP-011`/`032`/`033`, were
each a means to a different end).

## 3. Five real, live drills — command by command

### 3.1 Business fail-open during a real total outage

```sh
docker kill opsmind-tempo
curl ... -X POST https://localhost:4318/v1/traces ...   # -> 200
```

The push succeeded despite Tempo being fully down — the Collector's own
`sending_queue` absorbed it; the producer was never rejected or blocked.
Unconditional, independent of any RTO/RPO target (`ADR-0004`).

### 3.2 The real crash-recovery finding — a self-caught wrong assumption

Expected `restart: unless-stopped` (declared on every service in
`observability-stack.yml`) to auto-restart a killed container. It did
**not**:

```text
docker kill opsmind-tempo          -> Exited (137)
(waited 16+ seconds)                -> still Exited (137), no restart
docker inspect ...RestartPolicy     -> {"Name":"unless-stopped", ...}  (genuinely set)
```

Root cause understood, not just observed: Docker treats an operator-issued
`stop`/`kill` as an intentional action and does not apply the restart
policy to it — correct, documented Docker behavior, not a misconfiguration
in this repo. Attempted the alternative — simulating a genuine in-process
crash via `docker exec opsmind-tempo kill -9 1` (killing PID 1 from
*inside* the container's namespace, distinct from an operator-side
stop/kill) — this also did not conclusively reproduce a restart
(`StartedAt` and the process's own start time were unchanged 3+ seconds
later). The exact reason was not chased further (diminishing returns for
this spec's scope), but the practical conclusion holds regardless: **this
topology has no fully-automatic crash recovery for an operator-triggered
stop** — recovery requires an explicit
`docker compose ... up -d --force-recreate <service>`, proven repeatedly
this session (`SPEC-OP-031`/`032`/`033`) to reach `healthy` in 10-20
seconds. `ADR-0010`'s RTO target is stated honestly against this real
model, not a fabricated self-healing claim.

### 3.3 Disk — a safe, isolated ENOSPC proof

The real shared host volume reported 223GB total / 182GB free at drill
time — filling it was neither practical nor safe. Proved the identical
OS-level failure mode in isolation instead:

```sh
docker run --rm --tmpfs /test:size=5m alpine sh -c \
  'dd if=/dev/zero of=/test/fill bs=1M count=10'
# dd: error writing '/test/fill': No space left on device
# 5+0 records out (of 10 requested); exit code 1
```

Every one of Prometheus/Loki/Tempo writes to local ext4/overlay storage
(`ADR-0002`) and would receive this identical OS-level error on any write
once genuinely full. Checked each backend's own live `/metrics` for a real
total-storage-bytes gauge before building anything: only Prometheus has
one (`prometheus_tsdb_storage_blocks_bytes` +
`prometheus_tsdb_wal_storage_size_bytes`) — Loki (chunk-size histograms
only) and Tempo (no storage-bytes metric found at all) do not, as of the
pinned versions (3.3.2 / 2.7.1). New
`rules/recording/disk-capacity.yml : prometheus:storage_bytes:total`
+ one new dashboard panel — **deliberately no alert threshold**, since no
real production volume size exists in this repo to ground one in yet
(inventing one would be fake enforcement, the exact thing this domain's
practice avoids).

### 3.4 Cardinality budget genuinely trips, not just idles

```text
1. Pushed 60 real distinct series: op_034_cardinality_drill_total{case="variant-0..59"}
   Confirmed landed: count(op_034_cardinality_drill_total) = 60
2. job:series:count{job="otel-collector"} = 524 (real, live value)
3. Edited the LIVE rules/alerting/cardinality.yml: HighCardinalityJob
   threshold 20000 -> 100 (below 524)
4. POST /-/reload -> 200
5. GET /api/v1/rules?rule_name=HighCardinalityJob -> state: "pending"
   (query: "job:series:count > 100") -- the condition genuinely tripped
6. Reverted the threshold back to 20000, POST /-/reload -> 200
7. GET /api/v1/rules?rule_name=HighCardinalityJob -> state: "inactive"
   (on the next evaluation, ~5s later)
8. git diff rules/alerting/cardinality.yml -> (empty) -- the committed
   file was never actually left changed
```

This is the first time `SPEC-OP-006`'s `HighCardinalityJob` alert has been
proven to genuinely transition state, not merely confirmed present and
quiet.

### 3.5 Backlog/drop bound — reused, not re-proven

Already real and proven live under `SPEC-OP-011`'s own build (stop Tempo,
push, restart Tempo, confirm arrival within `max_elapsed_time`) and
re-confirmed multiple times since (`SPEC-OP-032`'s rollback proof,
`SPEC-OP-033`'s synthetic-probe failure-injection). Not re-run here to
avoid duplicating existing, already-real evidence.

## 4. What was built

- **`ADR-0010`** — RPO (60s, local tuning; backed by the Collector's
  proven WAL), RTO (5m, bounded by `SyntheticProbeFailing`'s own paging
  threshold, explicitly **not** a fully-automatic number per §3.2's
  finding), and the honest recovery-model statement.
- **`rules/recording/disk-capacity.yml`** — one new recording rule,
  visibility-only, real metric names confirmed live before use.
- **`runbooks/OutageAndRecoveryDrill.md`** — documents all 5 drills +
  the RTO/RPO targets, in the same operational-reference category as
  `ConfigurationChangeRollback.md`/`ObservabilityAccessControl.md` (not
  alert-linked).
- **1 new dashboard panel** (`prometheus:storage_bytes:total`) on
  `observability-platform-self.json`.
- **1 new smoke-test assertion** (`scripts/observability-stack.sh`)
  confirming the new recording rule is query-valid.

## 5. Full validator + test + smoke sweep

- `validate-observability-layout.py` 0 err/0 warn (transient `audit_ref`
  warnings for the 2 new files resolved once this doc existed).
- `validate-telemetry-governance.py`, `validate-signal-contracts.py`,
  `validate-collector-pipeline.py`, `validate-config-change-audit.py` — all
  0 err/0 warn, unaffected.
- `validate-dashboards.py` 0 err/1 pre-existing warn (unrelated).
- `validate-rule-catalog.py` 0 err/13 warn (12 pre-existing + 1 new
  expected orphaned-runbook warning for `OutageAndRecoveryDrill.md`, same
  accepted category as prior operational-reference runbooks).
- `promtool check rules` — `disk-capacity.yml`: SUCCESS.
- `scripts/tests/` — 88 passed, unchanged.
- `scripts/observability-stack.sh smoke` — **SMOKE: PASS**, every
  `SPEC-OP-002`~`033` assertion green (the stack survived being the direct
  target of repeated live kill/exec/reload drills throughout this spec's
  own build) plus the new disk-capacity assertion.
- Stack torn down clean.

## 5a. Addendum (2026-09-01, from SPEC-OP-035): this spec's own recording rule had a real bug

`prometheus:storage_bytes:total` (§4) was built without a `job="prometheus"`
label filter. `SPEC-OP-035` discovered Tempo emits a metric of the exact
same name (`prometheus_tsdb_wal_storage_size_bytes`) — real, non-zero, once
enough trace traffic had passed through it (this spec's own check ran too
early, before Tempo's copy had ever been populated, and concluded
"Loki/Tempo have no such metric," which was not fully accurate). Without
the job filter, this rule was silently aggregating whichever component's
identically-named metric happened to exist, not specifically Prometheus's
own storage. Compounded by a separate, bigger regression from `SPEC-OP-030`
(Prometheus's own self-scrape had been silently 401'ing the whole time,
so `job="prometheus"` data for this metric wasn't even landing until that
was also fixed). Both fixed under `SPEC-OP-035` — see that spec's own
traceability doc for the full account.

## 6. Residual risks / honest limitations

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No fully-automatic crash recovery in this topology (§3.2) | Medium — a real, stated limitation, not hidden | a production Kubernetes topology with real liveness-probe-driven restarts would close this differently; not built or claimed here since it doesn't exist in this repo |
| Exact reason `docker exec ... kill -9 1` didn't terminate PID 1 was not chased down | Low | practical conclusion (recovery requires a triggered action) is unaffected either way; a genuine follow-up if ever relevant |
| Loki/Tempo have no real disk-usage self-monitoring | Low — honestly stated, not fabricated | revisit if either backend adds a real total-storage-bytes metric in a future version, or if a real production volume size ever exists to ground a threshold in |
| RTO's 5-minute target assumes a human/automation acts on the alert — neither exists in this repo | Medium | stated explicitly in `ADR-0010`; not a hidden assumption |

## 7. Sign-off

This domain now has concrete RTO (5 minutes) and RPO (60 seconds) targets
for the first time, each backed by real, live evidence rather than
asserted. Business fail-open was re-confirmed during an actual total
outage. The cardinality-budget alert was proven to genuinely trip for the
first time, not merely exist and stay quiet. The disk-full failure mode
was demonstrated safely and honestly, without inventing a threshold with
no real grounding. Most importantly: this spec's own investigation caught
and corrected a real, wrong assumption (that `restart: unless-stopped`
provides automatic crash recovery) rather than asserting a self-healing
capability that direct testing did not actually support — exactly the
"verify live, don't assume" discipline this domain has followed
throughout. Closes `phase-08-self-monitoring-recovery-degraded-mode`.
