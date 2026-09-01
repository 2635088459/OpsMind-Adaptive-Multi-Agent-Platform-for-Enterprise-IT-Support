# DatabaseBrokerInfrastructure

> owner: platform-observability
> version: 2.0.0
> spec: SPEC-OP-029
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-029-traceability.md

Companion runbook for the **Database, Broker & Infrastructure** dashboard
(`dashboards/database-broker-infrastructure.json`). Covers both alerts
`SPEC-OP-029` adds: `PostgresConnectionPoolNearSaturation` and
`RabbitmqQueueBacklogHigh` — one file per this spec's own incident class,
same grouping convention as this domain's other business-signal runbooks.

**Scope update from `SPEC-OP-018`:** that spec shipped the
application-level half only (DB/AMQP client metrics from domain services)
and explicitly deferred the infrastructure-level half — `postgres_exporter`
and a RabbitMQ Prometheus endpoint — to this spec. Both now exist for real:
a dedicated `obs-postgres`/`obs-rabbitmq` pair in this stack's own
docker-compose (NOT the shared business-schema instances `local-platform.yml`
runs), scraped via `postgres_exporter` (no native Prometheus endpoint in
Postgres itself) and RabbitMQ's own built-in `rabbitmq_prometheus` plugin
(enabled at container startup), both wired through `SPEC-OP-012`'s file_sd
discovery — a new target file, no `prometheus.yml` edit needed.

## Impact

- `PostgresConnectionPoolNearSaturation`: new connections may start failing
  or queuing once `pg_settings_max_connections` is reached — a real
  capacity risk to every domain service sharing that Postgres instance.
- `RabbitmqQueueBacklogHigh`: consumers are falling behind producers —
  messages are delayed, not lost, but a sustained, growing backlog risks
  eventually exhausting broker disk/memory if it never recovers.

## Detection

- Firing expressions:
  - `postgres:connections:ratio > 0.8` for 10m
    (`sum(pg_stat_database_numbackends) / sum(pg_settings_max_connections)`)
  - `rabbitmq:queue_depth:sum > 1000` for 15m
    (`sum(rabbitmq_queue_messages)`)
- Dashboard: `dashboards/database-broker-infrastructure.json` — the
  "Infrastructure" row (real panels now, no longer a deferred text panel).
- Correlation entry point: cross-check the dashboard's application-level DB/
  AMQP panels (from `SPEC-OP-018`) for the same time window — a connection-
  pool or queue-depth alert firing alongside a rising application-level error
  rate points at a shared root cause; firing alone (application metrics
  normal) points at an infra-only capacity issue.

## Triage

1. Check which alert fired — they have unrelated root causes.
2. For `PostgresConnectionPoolNearSaturation`: check
   `pg_stat_database_numbackends` broken down by `datname` to see which
   database/service is holding the most connections — a connection leak in
   one service looks very different from genuine cross-service growth.
3. For `RabbitmqQueueBacklogHigh`: check `rabbitmq_queue_messages` broken
   down by `queue`/`vhost` to see which queue is backing up — one stuck
   consumer looks very different from a platform-wide slowdown.

## Mitigation

- `PostgresConnectionPoolNearSaturation`: no direct mitigation from this
  side — restarting a leaking service's connection pool, or raising
  `max_connections`, is that service's/domain's own operational call
  ([forbidden-business-writes §4](../docs/forbidden-business-writes.md)).
- `RabbitmqQueueBacklogHigh`: no direct mitigation from this side either —
  scaling consumers or investigating a stuck one is the owning domain's own
  call, not something this runbook instructs from the observability side.

## Resolution

- `PostgresConnectionPoolNearSaturation`: durable fix is the owning
  service's — a fixed connection leak, corrected pool sizing, or a
  `max_connections` change. Confirm resolution by watching
  `postgres:connections:ratio` return below the threshold.
- `RabbitmqQueueBacklogHigh`: durable fix is the owning domain's — restored/
  scaled consumers. Confirm resolution by watching `rabbitmq:queue_depth:sum`
  trend back down toward `0`.

## Rollback

Exact revert: `git revert <sha>` on this runbook / the two rule files
(`rules/recording/db-broker-infrastructure.yml`,
`rules/alerting/db-broker-infrastructure.yml`) / the 3 new compose services
(`obs-postgres`, `postgres-exporter`, `obs-rabbitmq`) /
`versions.env`'s 3 new entries; `promtool check rules`; recreate Prometheus
and remove the 3 new containers + their volumes.

## Escalation

- `PostgresConnectionPoolNearSaturation` (`warning`): opens a ticket against
  the owning service's on-call (per `datname`/`service_namespace` cross-
  reference) — domain 08 defines and detects the signal, it does not
  remediate it (ADR-0004).
- `RabbitmqQueueBacklogHigh` (`warning`): opens a ticket against the owning
  domain's on-call (per `queue`/`vhost` cross-reference), same reasoning.

## Post-incident

Link the traceability entry
(`docs/traceability/domains/08-observability-platform/SPEC-OP-029-traceability.md`).
Residual risk: `obs-postgres`/`obs-rabbitmq` are dedicated, empty proof
instances in THIS stack — they demonstrate the exporter integration works,
but do not scrape the REAL shared business-schema Postgres/RabbitMQ
`local-platform.yml` runs (a separate compose project, no shared network by
design — see `SPEC-OP-029`'s own traceability doc for why cross-compose
scraping was deliberately not attempted). A real production topology would
point these same exporters at the real instances instead.
