# DatabaseBrokerInfrastructure

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-018
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: n/a (view only)
> runbook: self
> rollback: git revert <sha>; re-run grafana provisioning
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-018-traceability.md

Companion runbook for the **Database, Broker & Infrastructure** dashboard
(`dashboards/database-broker-infrastructure.json`).

## Honest scope: what this dashboard does and does not show

This spec's goal names "Integrate DB, RabbitMQ, node/container exporters." Only
the **application-level** half is real today: DB and AMQP client metrics emitted
by domain services over OTLP, using the `db.*`/`amqp.*` namespaces already
contracted by `SPEC-OP-006`. The **infrastructure-level** half — `postgres_exporter`,
`rabbitmq_exporter`, `node_exporter` scraping the database/broker/host processes
themselves for queue depth, replication lag, disk, and CPU — does **not exist
in this stack yet**. `infrastructure/postgres/` and `infrastructure/rabbitmq/` are
still placeholder directories; that work is explicitly `SPEC-OP-029`'s scope. The
dashboard's own bottom row states this in-place rather than showing an empty or
fabricated panel.

## What each row shows (today)

- **Database**: operation rate and p95 latency by `db_system`/`db_operation`.
- **RabbitMQ**: publish/consume rate and latency by destination/operation.
- **Infrastructure** (deferred): a text panel naming exactly what is missing and
  which spec closes it, including the mechanism already in place to receive it —
  `SPEC-OP-012`'s `file_sd_configs` discovery (drop a new target JSON file, no
  `prometheus.yml` edit or restart needed).

## When SPEC-OP-029 lands

Add `postgres-exporter.json` / `rabbitmq-exporter.json` / `node-exporter.json`
under `prometheus/base/file_sd/`, and extend this dashboard's bottom row with real
panels (queue depth, connection pool saturation, replication lag, disk/CPU) — the
row is already reserved and named for exactly this.

## Rollback

`git revert` the dashboard/runbook change; re-run Grafana provisioning.
