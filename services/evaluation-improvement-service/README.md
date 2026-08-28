# evaluation-improvement-service

Domain 07 — Evaluation Improvement. Owns evaluation facts, gate decisions, candidate
proposals, and rollback recommendations for the OpsMind platform. **It never mutates
Agent, Prompt, Policy, Tool, Ticket, Workflow, or Memory state directly** — see
`docs/low-level-design/domains/07-evaluation-improvement/02-business-invariants`
(INV-EI-001) and `tests/architecture/test_no_production_mutation.py`.

## Status

**Phase 00 (engineering foundation) is complete — SPEC-EI-001, 002, and 003 are all
implemented:**

- `SPEC-EI-001` (Evaluation Module And Package Boundaries) — module/package skeleton,
  hexagonal layering, and every named use case from `13-package-and-class-design`
  implemented with real logic.
- `SPEC-EI-002` (Evaluation Schema Baseline) — real `evaluation` PostgreSQL schema
  (SQLAlchemy + Alembic), real Postgres-backed repositories for the six core
  aggregates plus gate policies/case-execution-results.
- `SPEC-EI-003` (Outbox Processed Event Audit Baseline) — real Postgres-backed
  outbox/processed-event/command-idempotency/audit repositories, completing all
  twelve `evaluation.*` tables.

Persistence backend is selected via `Settings.evaluation_persistence` ("postgres" by
default, "memory" for hermetic tests). Real RabbitMQ wiring for `EventPublisherPort`
itself, real LangSmith integration (`SPEC-EI-013`), and the full deterministic/
LLM-Judge grader catalog (`SPEC-EI-014`/`015`/`016`) are later specs — see each
module's own docstring for the specific deferral.

**Phase 01 (dataset and test assets) is complete — SPEC-EI-004 through 008:**

- `SPEC-EI-004` (Evaluation Dataset Aggregate) — real dataset deprecate/archive
  lifecycle (`POST .../deprecate`, `POST .../archive`) and versioned test-asset
  ownership (`POST .../versions` creates a new DRAFT version from a PUBLISHED parent,
  copying its test cases forward; `GET /evaluation/datasets?name=` reads the full
  lineage chain).
- `SPEC-EI-005` (Evaluation Test Case Schema Ground Truth) — the full test-case schema
  (ground truth, allowed/forbidden tools, verification condition, approval
  expectation) is now genuinely readable back, not just write-only
  (`GET .../cases`, `GET .../cases/{testCaseId}`).
- `SPEC-EI-006` (Golden Dataset Review Publish) — REVIEWING is a real, rejectable step
  of its own (`POST .../submit-review`, `POST .../reject-review`); `publish()` now
  requires the dataset already be REVIEWING instead of silently auto-elevating it.
- `SPEC-EI-007` (Dataset Artifact Hash Lineage) — a dataset-level `content_hash`
  (SHA-256 over every one of its own test cases' `input_hash`), `None` until
  `publish()` freezes it.
- `SPEC-EI-008` (Dataset Api Access Control) — every read endpoint now requires an
  authenticated evaluation role (`view_evaluation_data`), and a real, caller-asserted
  `tenant_id` (`X-Tenant-Id`) scopes both reads and writes — a cross-tenant dataset
  reads back as 404, never 403.

**Phase 02 (benchmark run and executor/runner) is under way — SPEC-EI-009 and 010:**

- `SPEC-EI-009` (Evaluation Run Aggregate State Machine) — a real case-level state
  machine (`CaseExecutionStatus`: COMPLETED/FAILED/SKIPPED); a runner exception is now
  caught and recorded as FAILED instead of propagating and blocking the run forever;
  a new `skip_case()` (`POST .../skip`); `finalize_scoring()` now finalizes a run with
  any FAILED/SKIPPED case as PARTIAL instead of COMPARING.
- `SPEC-EI-010` (Run Create Cancel Query Api) — `cancel_run()` is now idempotent
  (a resubmitted cancel against an already-CANCELLED run returns it rather than
  raising); `GET /evaluation/runs?dataset_id=&status=` gives real state visibility
  over every run against a dataset.

## Stack

Python 3.12+, FastAPI, Pydantic, SQLAlchemy, Alembic, PostgreSQL, pytest,
import-linter, testcontainers. (RabbitMQ and the LangSmith SDK are declared in this
spec's own LLD header as the service's eventual full stack but are not yet real
dependencies here — see `pyproject.toml`'s own comment.)

## Layout

```text
src/evaluationimprovement/
  domain/            # aggregates, value objects, state machines, domain events
  application/        # commands/views/ports, the 10 named use-case services
    services/
  infrastructure/      # persistence (in-memory + Postgres), graders, fake clients
    persistence/postgres/  # SQLAlchemy models, repositories, session, migrations
  interfaces/          # REST + admin FastAPI routers, DTO mapping, RBAC gate
  container.py         # composition root
  main.py               # FastAPI app factory
migrations/            # Alembic — five hand-written revisions (baseline schema,
                       #  outbox/audit tables, dataset content_hash, dataset
                       #  tenant_id, case_execution_result status)
tests/
  domain/               # aggregate + state-machine unit tests
  application/           # walking-skeleton pipeline tests against in-memory adapters
  integration/            # real Postgres via testcontainers (marker: integration)
  architecture/           # import-linter + layer-boundary + no-production-mutation
  contracts/               # event-envelope shape + INV-EI-003 safety-gate contract
  test_app.py               # end-to-end HTTP smoke test
```

## Running

```bash
uv sync
uv run pytest                         # unit + application + architecture + contracts
uv run pytest -m integration          # + real Postgres via testcontainers (needs Docker)
uv run lint-imports
uv run pyflakes src/evaluationimprovement tests
uv run alembic upgrade head            # apply the schema to DB_HOST/PORT/NAME/USERNAME/PASSWORD
uv run python -m evaluationimprovement.main   # serves on :8011
```

## Architecture rules (enforced by tests, not just convention)

- **Hexagonal layering**: `interfaces -> application -> domain`, `domain` never
  imports FastAPI/Pydantic/SQLAlchemy, `application` never imports `infrastructure`
  (`pyproject.toml` `[tool.importlinter]`, `tests/architecture/
  test_layer_boundaries.py`).
- **Only `container.py` wires concrete adapters** — every `interfaces` module reaches
  one through an `application.ports_in` Protocol
  (`tests/architecture/test_interfaces_boundary.py`).
- **No direct production mutation** — no function anywhere in this service is named
  after a mutating verb against a foreign domain's aggregate, and
  `AgentRuntimeEvaluationPort`/`PolicyApprovalPort` each expose exactly one read/
  request-only method (`tests/architecture/test_no_production_mutation.py`).
- **Deterministic-only safety gates** (INV-EI-003) — `EvaluateReleaseGateService` and
  `CompareRegressionService` only ever read `GraderType.DETERMINISTIC` scores when
  deciding a gate/critical-case outcome; an `LLM_JUDGE` score can never fail a release
  (`tests/contracts/test_safety_gate_determinism_contract.py`).
