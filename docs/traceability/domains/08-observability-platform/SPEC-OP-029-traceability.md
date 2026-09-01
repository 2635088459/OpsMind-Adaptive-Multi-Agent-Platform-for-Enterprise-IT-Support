# SPEC-OP-029 Traceability — PostgreSQL RabbitMQ And Connector Contract

> Domain: `08-observability-platform`
> Phase: `phase-06-cross-domain-contracts` (closes this phase)
> Status: implemented
> Verified: 2026-08-31
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
  runbooks/DatabaseBrokerInfrastructure.md              CHANGED (full rewrite — see §4)
  dashboards/database-broker-infrastructure.json        CHANGED (deferred row -> 4 real panels)
  rules/CATALOG.md                                      CHANGED (new row + moved out of the "deliberately alert-less" list)

infrastructure/docker-compose/observability-stack.yml   CHANGED (3 new services: obs-postgres, postgres-exporter, obs-rabbitmq)
.github/workflows/observability-platform-ci.yml         CHANGED (2 new rule files in promtool check rules)
scripts/observability-stack.sh                          CHANGED (job-up loop extended; new query_back assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-029-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-029-traceability.md       NEW (this file)
```

No change to `local-platform.yml` or any domain service — this spec is
entirely additive within `observability-stack.yml`'s own compose project.

## 3. Commands run and results (2026-08-31)

| Command | Result |
|---|---|
| `validate-observability-layout.py` | 0 errors (4 expected `audit_ref` warnings, cleared by this file; validates the 3 new pinned-image entries in `versions.env`) |
| `validate-telemetry-governance.py` | 0 errors, 0 warnings |
| `validate-signal-contracts.py` | 0 errors, 0 warnings |
| `validate-collector-pipeline.py` | 0 errors, 0 warnings |
| `validate-dashboards.py` | 0 errors (1 expected `audit_ref` warning) |
| `validate-rule-catalog.py` | 0 errors, 10 warnings (unchanged) |
| `docker compose ... config` | renders cleanly with 3 new services |
| `promtool check rules` (both new files) | SUCCESS — 2 recording + 2 alerting |
| `python -m unittest discover -s scripts/tests` | 82 passed, unaffected |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** (see §5) |
| `scripts/observability-stack.sh down` | stack + network removed; 2 named volumes needed manual follow-up (see §6) |

## 4. Real architecture decision: a dedicated infra pair, not cross-compose coupling

`infrastructure/docker-compose/local-platform.yml` already runs a real,
shared Postgres+RabbitMQ pair for domain services. Pointing
`postgres_exporter`/RabbitMQ's Prometheus plugin at THOSE real instances was
considered and deliberately rejected: `observability-stack.yml`'s own header
comment already states this stack is "independent of local-platform.yml and
can run alongside it" — coupling this domain's own CI/smoke test to another
compose project being simultaneously up (a project this domain does not own
and has no control over the lifecycle of) would break that independence and
make this domain's own smoke test flaky in any environment where
`local-platform.yml` isn't already running (e.g. a bare CI runner).

Instead, a dedicated, throwaway `obs-postgres`/`obs-rabbitmq` pair was added
directly to `observability-stack.yml` — small resource footprint (256M/384M
limits), real containers, purely so the exporters have something real to
connect to. This proves the INTEGRATION MECHANISM (exporter deployment,
file_sd wiring, recording/alerting rules, dashboard panels) rather than
scraping one specific real instance — a production topology would point the
same exporters at the real shared Postgres/RabbitMQ instead, which requires
no code change here, only a different `DATA_SOURCE_NAME`/connection target.

Also a real, deliberate architectural asymmetry between the two exporters:
`postgres_exporter` is a genuine separate sidecar container (Postgres has no
native Prometheus endpoint), while RabbitMQ uses its OWN built-in
`rabbitmq_prometheus` plugin (ships with the image since 3.8+, just not
enabled by default — enabled here via a `command` override at container
startup). No separate `rabbitmq_exporter` container was added; one isn't
needed.

## 5. Real docker-compose verification (2026-08-31)

Brought up the full stack (now 9 containers) and confirmed, via real
scraped data rather than just config inspection:

- `pg_up` == `1` — `postgres_exporter` genuinely connected to `obs-postgres`
  and reports it healthy (this is `postgres_exporter`'s own connectivity-
  health metric, not a generic container-up check).
- `pg_settings_max_connections` scraped — a real, standard
  `postgres_exporter` metric, confirming the `pg_settings` collector is
  active.
- `rabbitmq_identity_info` scraped — a real, standard `rabbitmq_prometheus`
  metric that only exists if the plugin is actually enabled and serving
  requests, not merely that the container started (a weaker check like
  `up{job="rabbitmq"}` alone would not have distinguished "container up" from
  "plugin actually enabled").
- `postgres:connections:ratio` and `rabbitmq:queue_depth:sum` both
  query-valid.
- `PostgresConnectionPoolNearSaturation` and `RabbitmqQueueBacklogHigh` both
  loaded via Prometheus's own `/api/v1/rules`.
- Every `SPEC-OP-002~028` assertion in the same run stayed green — the first
  time this domain has added new containers to the compose stack since
  `SPEC-OP-002`'s original 6, and every prior spec's own live proof still
  held with 3 more containers present.

## 6. Minor observation: an orphaned-volume teardown quirk

`scripts/observability-stack.sh down` (`docker compose down -v
--remove-orphans`) correctly removed all 9 containers and the network, but
left the 2 brand-new named volumes
(`opsmind-observability_obs-postgres-data`,
`opsmind-observability_obs-rabbitmq-data`) behind on the very first
teardown. Cleaned up manually (`docker volume rm`) rather than silently
leaving them, or silently assuming `down -v` is fully reliable for
newly-added volumes without checking. Not yet root-caused (a Compose
version quirk on first-ever teardown of a newly-declared volume is one
plausible explanation) — flagged here rather than hidden, in case it
recurs on a future teardown and needs real investigation.

## 7. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `obs-postgres`/`obs-rabbitmq` are empty proof instances, not the real shared business-schema Postgres/RabbitMQ | Low (by design — see §4) | a production topology points the same exporters at the real instances; no code change needed here beyond a connection target |
| The teardown quirk in §6 is not root-caused | Low | flagged for investigation if it recurs; a manual `docker volume rm` is a trivial, safe workaround either way |
| `PostgresConnectionPoolNearSaturation`/`RabbitmqQueueBacklogHigh` thresholds (80%, 1000 messages) are reasonable defaults, not derived from real production traffic patterns | Low | acceptable starting point; re-tuning is a data-driven follow-up once real load exists |

## 8. Sign-off

The infrastructure-level exporter gap `SPEC-OP-018` explicitly named and
deferred is now real: genuine `postgres_exporter` and RabbitMQ's own
built-in Prometheus plugin, proven scraping via real, distinctive
per-exporter metrics (not a generic health check), wired through the exact
`file_sd` mechanism already prepared for this. A dedicated, throwaway infra
pair was added as a deliberate architecture decision to avoid coupling this
domain's CI to another compose project's lifecycle, documented explicitly
rather than left implicit. Adding 3 new containers to the stack did not
disturb any of the prior 28 specs' own live proofs, confirmed by the same
full smoke run. This closes `phase-06-cross-domain-contracts`
(`SPEC-OP-025` through `SPEC-OP-029`) of the observability-platform domain
roadmap in full.
