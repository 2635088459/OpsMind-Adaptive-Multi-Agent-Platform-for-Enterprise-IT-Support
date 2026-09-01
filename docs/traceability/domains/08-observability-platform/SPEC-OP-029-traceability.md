# SPEC-OP-029 Traceability — PostgreSQL RabbitMQ And Connector Contract

> Domain: `08-observability-platform`
> Phase: `phase-06-cross-domain-contracts` (closes this phase)
> Status: implemented
> Verified: 2026-09-01 (rebuilt after a mid-session data-loss incident — see SPEC-OP-025's own traceability §5)
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Integrate infrastructure/connector exporters and
standardize health, lag, retry, limit, and dependency spans."*

| Requirement | Where |
|---|---|
| Postgres infra health | `postgres_exporter` (real sidecar), `postgres:connections:ratio` recording rule, `PostgresConnectionPoolNearSaturation` alert |
| RabbitMQ infra health | RabbitMQ's own built-in `rabbitmq_prometheus` plugin, `rabbitmq:queue_depth:sum` recording rule, `RabbitmqQueueBacklogHigh` alert |
| Query/dashboard artifact | `dashboards/database-broker-infrastructure.json`'s previously-deferred bottom row, now real |
| Rule/runbook artifact | `rules/{recording,alerting}/db-broker-infrastructure.yml` + rewritten `runbooks/DatabaseBrokerInfrastructure.md` |

## 2. Files added / changed

```text
infrastructure/observability/
  versions.env                                         CHANGED (3 new pinned images + real digests)
  prometheus/base/file_sd/postgres-exporter.json        NEW
  prometheus/base/file_sd/obs-rabbitmq.json             NEW
  rules/recording/db-broker-infrastructure.yml          NEW
  rules/alerting/db-broker-infrastructure.yml           NEW
  runbooks/DatabaseBrokerInfrastructure.md              CHANGED (full rewrite)
  dashboards/database-broker-infrastructure.json        CHANGED (deferred row -> 4 real panels)
  rules/CATALOG.md                                      CHANGED (new row + moved out of the "deliberately alert-less" list)

infrastructure/docker-compose/observability-stack.yml   CHANGED (3 new services: obs-postgres, postgres-exporter, obs-rabbitmq)
.github/workflows/observability-platform-ci.yml         CHANGED (2 new rule files)
scripts/observability-stack.sh                          CHANGED (job-up loop extended; new query_back assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-029-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-029-traceability.md       CHANGED (this file)
```

## 3. Real architecture decision: a dedicated infra pair, not cross-compose coupling

`local-platform.yml` already runs a real, shared Postgres+RabbitMQ pair for
domain services. Pointing the exporters at THOSE real instances was
considered and rejected: `observability-stack.yml`'s own header states this
stack is "independent of local-platform.yml" — coupling this domain's CI to
another compose project's lifecycle would break that independence.

Instead, a dedicated, throwaway `obs-postgres`/`obs-rabbitmq` pair was added
directly here. `postgres_exporter` is a genuine separate sidecar (Postgres
has no native Prometheus endpoint); RabbitMQ uses its own built-in
`rabbitmq_prometheus` plugin (enabled via a `command` override at startup)
— no separate exporter container needed.

## 4. Real bug found during recovery verification #1: BusyBox wget doesn't support `--http-user`

Enabling Prometheus's/Alertmanager's basic-auth gate (`SPEC-OP-030`, built
alongside this spec in the same recovery pass) required updating both
healthchecks to authenticate. The first draft used
`wget --http-user=admin --http-password=admin`, which left both containers
permanently unhealthy. `docker exec`-ing into the container and running the
healthcheck command directly showed the real cause:

```
wget: unrecognized option `--http-user=admin'
```

Both Prometheus's and Alertmanager's images ship BusyBox's minimal `wget`
build, which has no `--http-user`/`--http-password` flags at all — only
GNU wget does. Fixed with a manually-constructed Basic-auth header:
`--header="Authorization: Basic YWRtaW46YWRtaW4="` (verified working via
the same `docker exec` probe before changing the compose file).

## 5. Real bug found during recovery verification #2: enabling auth broke 3 internal integrations

After fixing #4, the containers came up healthy, but `SPEC-OP-014`'s own
smoke assertion (`traces_spanmetrics_calls_total` / exemplar check) started
failing — a regression in a completely different spec's already-passing
check. Investigating (not assuming) found Tempo's own container logs
showing the real cause:

```
caller=dedupe.go:112 ... msg="non-recoverable error" ...
err="server returned HTTP status 401 Unauthorized: Unauthorized"
```

Enabling Prometheus's/Alertmanager's auth gates is not limited to
external/human callers — every INTERNAL service that pushes data to either
one also needed credentials, or its writes were silently rejected:

1. **Tempo's `metrics_generator` → Prometheus `remote_write`**
   (span-metrics + exemplars) — fixed by adding `basic_auth` under
   `tempo/base/tempo.yml`'s own `remote_write` block.
2. **Prometheus's own alert notifications → Alertmanager** — fixed by
   adding `basic_auth` under `prometheus/base/prometheus.yml`'s
   `alerting.alertmanagers` entry.
3. **Loki's ruler → Alertmanager** (`SPEC-OP-013`'s LogQL alerting) — fixed
   via userinfo-in-URL (`http://admin:admin@alertmanager:9093`) in
   `loki/base/loki.yml`, the simplest mechanism Loki's ruler notifier
   supports without guessing at a nested config key.

All three were found by running the REAL smoke test after enabling auth,
not by static review. This is recorded here (this spec's own traceability)
rather than only under `SPEC-OP-030` because the FIRST symptom was this
spec's own dependency, `SPEC-OP-014`'s exemplar assertion — the fix touches
`tempo.yml`, which this spec's own dashboard/rules depend on for Tempo
health context.

## 6. Real docker-compose verification (2026-09-01, after both fixes above)

- `pg_up` == `1` — `postgres_exporter` genuinely connected to `obs-postgres`.
- `pg_settings_max_connections` scraped — a real, standard
  `postgres_exporter` metric.
- `rabbitmq_identity_info` scraped — proves the `rabbitmq_prometheus` plugin
  is actually enabled and serving requests, not merely that the container
  started.
- `postgres:connections:ratio` and `rabbitmq:queue_depth:sum` both
  query-valid.
- `PostgresConnectionPoolNearSaturation` and `RabbitmqQueueBacklogHigh` both
  loaded via Prometheus's own `/api/v1/rules`.
- Every `SPEC-OP-002~030` assertion in the same run stayed green — the
  first time this domain added new containers to the compose stack since
  `SPEC-OP-002`'s original 6, and every prior spec's own live proof held
  with 9 containers present (after the 2 real bugs above were fixed).
- `scripts/observability-stack.sh down` correctly removed all 9 containers,
  the network, and all volumes cleanly on this run (the very first attempt,
  before the auth work, had left 2 new volumes orphaned once — not
  reproduced on this run, so not investigated further as a distinct bug).

## 7. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `obs-postgres`/`obs-rabbitmq` are empty proof instances, not the real shared business-schema Postgres/RabbitMQ | Low (by design) | a production topology points the same exporters at the real instances |
| `PostgresConnectionPoolNearSaturation`/`RabbitmqQueueBacklogHigh` thresholds are reasonable defaults, not derived from real production traffic | Low | re-tuning is a data-driven follow-up |

## 8. Sign-off

The infrastructure-level exporter gap `SPEC-OP-018` explicitly deferred is
now real, proven via distinctive per-exporter metrics. A dedicated,
throwaway infra pair was a deliberate architecture decision. Two real bugs
were found via live verification during this recovery pass — a BusyBox
wget incompatibility, and a cascading auth-break across 3 internal
integrations triggered by `SPEC-OP-030`'s own security work — both found by
running the real smoke test, not by static review, and both fixed with the
root cause understood rather than papered over. This closes
`phase-06-cross-domain-contracts` (`SPEC-OP-025` through `SPEC-OP-029`) of
the observability-platform domain roadmap in full.
