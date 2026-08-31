# SPEC-OP-018 Traceability — Database, Broker, And Infrastructure Dashboards

> Domain: `08-observability-platform`
> Phase: `phase-04-dashboards-correlation-analysis`
> Status: implemented
> Verified: 2026-08-31 (application-level panels proven; infrastructure-level gap
> stated explicitly, not silently omitted)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Integrate DB, RabbitMQ, node/container exporters for pools,
queries, lag, disk, and CPU.*

| Spec area | Where |
|---|---|
| DB (application-level) | DB operation rate/p95-latency by `db_system`/`db_operation` (`SPEC-OP-006` `db.*` namespace) |
| RabbitMQ (application-level) | AMQP publish/consume rate + p95 latency by destination/operation (`amqp.*` namespace) |
| node/container exporters, queue depth, replication lag, disk, CPU | **not built** — see §4 |

## 2. Files added / changed

```text
infrastructure/observability/
  dashboards/database-broker-infrastructure.json   NEW
  runbooks/DatabaseBrokerInfrastructure.md         NEW

docs/specs/domains/08-observability-platform/SPEC-OP-018-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-018-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `GET /api/search` (Grafana) | dashboard listed with correct `uid`/`tags` |
| `db_client_operation_duration_seconds` / `amqp_publish_duration_seconds` queries | already proven against real pushed data in `SPEC-OP-016`'s verification pass (same underlying metrics) |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** |

## 4. Honest half-scope

The goal names infrastructure exporters explicitly. Checked at the very start of
this domain's work (and reconfirmed here): `infrastructure/postgres/` and
`infrastructure/rabbitmq/` are still placeholder (`.gitkeep`) directories — no
`postgres_exporter`, `rabbitmq_exporter`, or `node_exporter` exists in this stack.
That integration is explicitly `SPEC-OP-029`'s scope. Building fake infrastructure
panels now (with no exporter behind them) would be exactly the kind of "looks done,
isn't" work this domain's build log has consistently avoided. The dashboard's own
bottom row is a text panel stating this plainly, naming the exact spec, and naming
the mechanism already built and ready for it: `SPEC-OP-012`'s `file_sd_configs`
discovery — a new exporter target is a new JSON file under `prometheus/base/file_sd/`,
zero `prometheus.yml` edit or restart needed.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No infrastructure-level DB/broker/host visibility exists yet | Medium (explicit, not silent) | `SPEC-OP-029` |

## 6. Sign-off

Application-level DB/broker dashboards are real and proven. The infrastructure
gap is named, not hidden, with the exact spec and mechanism that closes it.
`SPEC-OP-019` (Agent/LLM Cost And Capacity Dashboard) continues phase-04.
