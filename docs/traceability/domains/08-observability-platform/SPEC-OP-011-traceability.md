# SPEC-OP-011 Traceability — Collector Batch, Retry, and Backpressure

> Domain: `08-observability-platform`
> Phase: `phase-02-collector-intake-processing` (closes this phase)
> Status: implemented
> Verified: 2026-08-31 (validators pass; otelcol validate passes after fixing a real
> missing create_directory option; a real Tempo outage was manually induced and
> recovered from, proving the WAL + retry contract, not just its config)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Configure batch, queues, retries, WAL/file storage, throttling, drop
accounting, and load budgets.*

| Spec area | Where |
|---|---|
| batch / queues / retries | already existed (`SPEC-OP-002`) — unchanged |
| WAL / file storage | NEW `file_storage` extension + `sending_queue.storage: file_storage` on both exporters |
| throttling | `memory_limiter` (already existed) is the mechanism; NEW `otelcol_receiver_refused_spans`-based alert makes it observable |
| drop accounting | NEW `rules/{recording,alerting}/collector-resilience.yml` against real, verified self-metrics |
| load budgets | already established by the local-vs-base overlay pattern (`queue_size` 1000→400, `max_elapsed_time` 120s→60s); now formally covered by this spec's rules |

## 2. Files added / changed

```text
infrastructure/observability/
  collector/base/config.yaml                 CHANGED (file_storage extension;
                                              sending_queue.storage + num_consumers
                                              on otlp/tempo + otlphttp/loki)
  rules/recording/collector-resilience.yml   NEW
  rules/alerting/collector-resilience.yml    NEW
  runbooks/CollectorBackpressure.md          NEW

infrastructure/docker-compose/observability-stack.yml   CHANGED (otel-collector-queue-data
                                                         volume; user: "0:0")
.github/workflows/observability-platform-ci.yml         CHANGED (promtool check for the 2 new rule files)
scripts/observability-stack.sh                          CHANGED (2 new recording-rule/
                                                         self-metric smoke assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-011-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-011-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `otelcol validate` (first draft, `file_storage` without `create_directory`) | **failed** — `directory must exist: stat /var/lib/otelcol/file_storage: no such file or directory` |
| `otelcol validate` (fixed: `create_directory: true`) | exit 0 |
| `docker compose up` (first attempt, before `user: "0:0"`) | **collector unhealthy** — `open /var/lib/otelcol/file_storage/exporter_otlp_tempo_traces: permission denied` |
| `docker compose up` (with `user: "0:0"`) | healthy |
| Manual outage drill: `docker stop opsmind-tempo`; push a tagged trace; `docker logs` | real `retry_sender.go: Exporting failed. Will retry the request after interval` lines observed; collector stayed `healthy` throughout |
| `docker start opsmind-tempo`; wait ~15s; `curl .../api/traces/<id>` | **trace found** — WAL + retry delivered it after the outage ended |
| `docker run … promtool check rules …/collector-resilience.yml` (both) | SUCCESS — 3 recording + 3 alerting |
| `uv run --with pyyaml python scripts/validate-observability-layout.py` | 0 errors (3 `audit_ref` warnings, cleared by this file) |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — including 2 new assertions: the `otelcol:exporter_queue_utilization:ratio` recording rule evaluates, and `otelcol_exporter_queue_capacity` is a real scraped series. Every `SPEC-OP-002`~`010` assertion in the same run stayed green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Real self-metrics, verified live (not assumed)

Queried a running collector's `:8888/metrics` directly rather than guessing OTel
Collector metric names (a lesson carried from `SPEC-OP-009`'s routing-connector
surprise): `otelcol_exporter_queue_size` / `otelcol_exporter_queue_capacity` are
always present; `otelcol_exporter_send_failed_spans` / `otelcol_exporter_sent_spans`
only register **after** an actual send attempt of that outcome occurs (confirmed by
their absence before the outage drill and presence after); `otelcol_receiver_refused_spans`
is present from startup at `0`. Every metric referenced in the new rules was
confirmed present this way before the rule was written.

## 5. Two real bugs found and fixed

1. **`file_storage` needs `create_directory: true`** — `otelcol validate` refused to
   even start without it (the directory doesn't pre-exist in a fresh container).
2. **The collector's default nonroot user cannot write a freshly-created Docker
   volume** — first `up` attempt left the container permanently unhealthy
   (`permission denied`). Fixed with `user: "0:0"` (numeric — the scratch-based
   image has no `/etc/passwd` to resolve a bare `"root"` against, which was tried
   first and itself failed with `unable to find user root: no matching entries in
   passwd file`).

## 6. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `user: "0:0"` runs the collector as root inside its container | Low | acceptable for local dev; a production topology spec should instead `chown` the volume to the image's actual nonroot UID at provisioning time rather than running as root |
| `queue_size` / `num_consumers` / `max_elapsed_time` are laptop-scale estimates | Low | `SPEC-OP-012`+/production topology sets real numbers against real traffic |
| No automated test of the outage/recovery scenario (it was a manual drill) | Medium | scripting a mid-test sibling-container stop/start into `scripts/observability-stack.sh smoke` is real future work; not done here to avoid extending an already-long smoke run with a ~30s outage-and-recovery pause on every CI run |

## 7. Sign-off

The Collector's own resilience — durable queuing across a restart, retry through a
real backend outage, and observability into queue/failure/throttling via metrics
verified live against the running binary — is real and proven, not merely
configured. Two genuine bugs were found and fixed in the process. This closes
**phase-02 (Collector Intake And Processing, `SPEC-OP-008`~`011`)** for domain 08.
`SPEC-OP-012` (Prometheus Metrics Backend) opens phase-03 (Telemetry Backends And
Retention).
