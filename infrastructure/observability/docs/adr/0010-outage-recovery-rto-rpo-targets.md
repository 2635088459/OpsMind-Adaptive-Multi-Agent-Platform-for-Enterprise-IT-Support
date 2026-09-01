# ADR-0010: Outage recovery targets (RTO/RPO) and the real recovery model for this topology

> Status: Accepted
> Date: 2026-09-01
> Spec: SPEC-OP-034
> Deciders: platform-observability

## Context

`SPEC-OP-034`'s objective is to "exercise outages, disk/cardinality/backlog/
drop with business fail-open, RTO/RPO, and recovery." No spec before this
one had ever stated a concrete RTO (Recovery Time Objective) or RPO
(Recovery Point Objective) for this domain, nor genuinely exercised a
component outage against a stated target — earlier specs (`SPEC-OP-011`,
`SPEC-OP-032`, `SPEC-OP-033`) each stopped/restarted a backend as a means to
another end (proving WAL durability, a rollback, a synthetic-probe failure
mode), but never as a dedicated RTO/RPO drill.

Investigating this live surfaced a real, previously-unknown fact about this
Docker Compose topology's actual recovery behavior (§2), which materially
changes what RTO honestly means here.

## Decision

### RPO (data-loss bound) — backlog/drop

Governed entirely by the Collector's own `sending_queue`/`retry_on_failure`
tuning (`SPEC-OP-011`), unchanged by this spec:

- **An outage shorter than `retry_on_failure.max_elapsed_time`
  (120s base/production tuning, 60s local laptop tuning) loses ZERO data**
  — every item keeps retrying from the persistent WAL until the backend
  recovers. Already proven live under `SPEC-OP-011`'s own build (stop
  Tempo, push, restart Tempo, confirm arrival) and re-confirmed multiple
  times since (`SPEC-OP-032`, `SPEC-OP-033`).
- **An outage longer than that window risks dropping items**, bounded by
  `sending_queue.queue_size` (1000 base / 400 local) — i.e. the worst-case
  loss is approximately one queue's worth of telemetry, never unbounded.
- **RPO target: 60 seconds** (the local-laptop tuning value, the one this
  environment actually runs) — any real outage shorter than this is
  guaranteed zero telemetry loss.

### Business fail-open (unconditional, independent of RTO/RPO)

Re-confirmed live under this spec (§3.1): a real OTLP push to the Collector
returns `200` even while Tempo is fully down — the Collector accepts and
queues; it never rejects or blocks the producer. This is unconditional and
does not depend on meeting any RTO/RPO target — `ADR-0004`'s guarantee
holds regardless of how long a backend stays down.

### RTO (time-to-recover) — a genuine finding changes what this means

**Docker's `restart: unless-stopped` policy does NOT auto-restart a
container after an operator-initiated `docker stop` or `docker kill`** —
confirmed empirically (§3.2): killing Tempo via `docker kill` left it
`Exited (137)` indefinitely with no automatic restart, even though the
compose file declares `restart: unless-stopped` on every service. This is
documented, correct Docker behavior (`stop`/`kill` are explicit lifecycle
commands the daemon treats as intentional, distinct from the container's
own process crashing internally) — but it means **this topology has no
fully-automatic crash recovery for an operator/orchestrator-initiated
stop**, only for a genuine in-process crash (which this session's own
attempt to simulate via `docker exec ... kill -9 1` did not conclusively
reproduce either — see the traceability doc's own honest account).

Given that, RTO here is honestly bounded by **detection + a triggered
recreate**, not a fully automatic mechanism:

- **Detection**: the synthetic probe (`SPEC-OP-033`) fails within one probe
  interval (60s) + its own query-wait (13s) ≈ 75s worst case; `TargetDown`
  (`up == 0`, `SPEC-OP-002`) fires after 2m; `SyntheticProbeFailing` pages
  after 5m sustained.
- **Recovery action**: `docker compose ... up -d --force-recreate <service>`
  — proven repeatedly this session to bring a stopped/crashed backend back
  to `healthy` in **10-20 seconds** (observed across every redeploy in
  `SPEC-OP-031`/`032`/`033`).
- **RTO target: 5 minutes** (bounded by `SyntheticProbeFailing`'s own
  paging threshold) from outage start to a human/automation running the
  recovery command and the backend reporting healthy again. This is
  **not** a fully-automatic number — it assumes a human or an automation
  layer (neither exists in this repo today) acts on the alert.

## Consequences

- RTO is honestly stated as requiring a triggered action, not a fully
  self-healing system — a real, stated limitation of this Docker Compose
  local-dev topology, not glossed over. A production Kubernetes topology
  with a real liveness-probe-driven restart policy would close this gap
  differently (a K8s liveness probe restarts on failed health checks
  regardless of how the process last exited) — noted as a real difference
  from this repo's actual, tested topology, not assumed to already exist.
- RPO's 60s number is specifically the LOCAL overlay's tuning; the
  production overlay's `max_elapsed_time: 120s` gives a looser real bound
  there — both are real, tested values, not invented.
- Disk-full behavior is real OS-level `ENOSPC` behavior (confirmed via an
  isolated, safe tmpfs demonstration rather than filling this shared host's
  real disk — see traceability doc §3.3), identical across Prometheus/
  Loki/Tempo since all three write to local ext4/overlay storage
  (`ADR-0002`). No new disk-usage alert threshold is invented for Loki/
  Tempo specifically, since neither exposes a real total-storage-bytes
  metric as of the pinned versions (loki 3.3.2, tempo 2.7.1) — a stated,
  honest gap, not fabricated enforcement.

## Alternatives considered

- **Fill this shared host's real disk volumes to prove ENOSPC handling
  directly inside Loki/Tempo/Prometheus.** Rejected: impractical (the real
  volume reported 223GB total, 182GB free) and unsafe in a shared
  environment. An isolated tmpfs drill proves the identical OS-level
  mechanism safely instead.
- **Simulate a "crash" via `docker kill`/`docker stop` and claim automatic
  recovery.** Rejected once the real behavior was discovered — this would
  have been a false claim; the ADR states the real, narrower finding
  instead.
- **Invent a Loki/Tempo disk-usage threshold from an assumed production
  volume size.** Rejected: no real production topology with a real volume
  size exists in this repo yet to ground a number in; a fabricated
  threshold would be exactly the "fake enforcement" this domain's practice
  avoids.
