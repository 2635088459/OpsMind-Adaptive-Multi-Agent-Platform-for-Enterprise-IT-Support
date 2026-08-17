# Memory Knowledge Service

## Service Purpose

`memory-knowledge-service` owns Working Memory, long-term Memory (candidates,
versions, retention), Knowledge Documents, and the Knowledge Graph for OpsMind. It
gives Agent Runtime provenance-tagged retrieval evidence (hybrid search + bounded
graph expansion) and turns resolved Ticket/Workflow outcomes into reviewed,
published, redacted long-term memory — without ever writing Ticket or Workflow
state directly, and without ever exposing an unredacted or unauthorized result.

## Status

All 32 specs across all 9 implementation phases are complete
(`SPEC-MK-001` – `SPEC-MK-032`; see
[Traceability entries](../../docs/specs/domains/04-memory-knowledge/) — every
`SPEC-MK-0xx/traceability-entry.yaml` is `status: implemented`). The service is
release-ready per its own 14-testing-strategy checklist
([SPEC-MK-032](../../docs/specs/domains/04-memory-knowledge/SPEC-MK-032-final-coverage-audit-release-readiness/)).

## Prerequisites

- Python 3.13+
- [`uv`](https://docs.astral.sh/uv/) (dependency management and task running)
- Docker (for `testcontainers`-backed integration tests, and for local infrastructure)

## Run Locally

```bash
uv sync
uv run python -m memoryknowledge.main
```

Serves on `http://localhost:8010`. `GET /health` returns `{"status": "UP"}`.

Requires a reachable Postgres instance (`memory_persistence` defaults to
`"postgres"`) with migrations applied — see **Database Migrations** below. Set
`MEMORY_PERSISTENCE=memory` to run instead against SPEC-MK-001's fast, non-durable
in-memory adapters (useful for a quick local smoke test with no database at all).

## Run Tests

Fast unit tests only (no Docker required):

```bash
uv run pytest -q -m unit
```

Integration tests (requires Docker — spins up ephemeral Postgres and RabbitMQ
containers via `testcontainers`, never touches a shared/persistent instance):

```bash
uv run pytest -q -m integration
```

Full suite:

```bash
uv run pytest -q
```

Static checks:

```bash
uv run python -m pyflakes src tests
uv run lint-imports
```

## Database Migrations

```bash
uv run alembic upgrade head
```

Applies every migration under `migrations/versions/` to the `memory` schema (a
dedicated schema inside the shared Postgres database — see
[07-data-model](../../docs/low-level-design/domains/04-memory-knowledge/07-data-model/README_CN.md)).

## Configuration Variables

| Variable | Purpose | Default |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Shared PostgreSQL connection | `localhost:5432/ticket_workflow` |
| `MEMORY_PERSISTENCE` | `postgres` (real) or `memory` (in-memory, hermetic) | `postgres` |
| `EVENT_PUBLISHER_ADAPTER` | `logging` (inert, safe default) or `rabbitmq` (real broker) | `logging` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`, `RABBITMQ_EXCHANGE` | RabbitMQ connection (only used when `EVENT_PUBLISHER_ADAPTER=rabbitmq`) | see `settings.py` |
| `OTEL_EXPORTER` | `console` (stdout, no network) or `otlp` (real collector) | `console` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry OTLP collector endpoint | `localhost:4317` |
| `MEMORY_SERVICE_NAME`, `OTEL_SERVICE_NAME` | Service identity for logs/traces/metrics | `memory-knowledge-service` |

## Start Local Infrastructure

```bash
docker compose -f ../../infrastructure/docker-compose/local-platform.yml up -d
```

Copy `../../infrastructure/docker-compose/.env.example` to `.env` in the same
directory and adjust credentials before starting, if you need non-default values.
Never commit a real `.env` file.

## API Surface

All routes are internal-only (`/internal/memory/v1/...`), never exposed to end
users directly.

- **Runtime-facing** (`/internal/memory/v1/...`): `POST /search` (hybrid retrieval
  with provenance + bounded graph paths), `PATCH` / `GET /working-memory/{id}`
  (per-ticket-cycle scratch state).
- **Event consumers** (`/internal/memory/v1/events/...`): `ticket-resolved`,
  `ticket-closed` (from `ticket-workflow-service`), `workflow-completed`,
  `workflow-failed` (from `agent-runtime-service`) — each idempotent
  (processed-event dedup) and each a candidate-extraction trigger, never a direct
  active-memory write.
- **Admin** (`/internal/memory/v1/admin/...`, requires `X-Actor-Id`): document
  ingest/retry/reindex, candidate extract/validate/reject/approve, memory
  deprecate/delete, working-memory archive/delete, graph node lookup, outbox
  dispatch/replay, audit-event/poison-event visibility and quarantine, and the
  three recovery scans (ingestion, publish-graph, retention).

See [05-api-contracts](../../docs/low-level-design/domains/04-memory-knowledge/05-api-contracts/README_CN.md)
for the full contract and [06-event-contracts](../../docs/low-level-design/domains/04-memory-knowledge/06-event-contracts/README_CN.md)
for every consumed/published event shape.

## Architecture

Hexagonal (ports and adapters): `domain` → `application` → `infrastructure` /
`interfaces`, enforced by `import-linter` contracts
(`pyproject.toml`) and `tests/architecture/`. `memoryknowledge/container.py` is the
one composition root allowed to wire concrete adapters. See
[13-package-and-class-design](../../docs/low-level-design/domains/04-memory-knowledge/13-package-and-class-design/README_CN.md).

## Design Links

- [Implementation plans (phase-00 – phase-09)](../../docs/implementation-plans/domains/04-memory-knowledge/)
- [Specs (SPEC-MK-001 – SPEC-MK-032)](../../docs/specs/domains/04-memory-knowledge/)
- [Low-level design (14 aspects)](../../docs/low-level-design/domains/04-memory-knowledge/)
