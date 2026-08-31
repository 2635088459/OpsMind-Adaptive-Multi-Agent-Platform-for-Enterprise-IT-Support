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

**Phase 02 (benchmark run and executor/runner) is complete — SPEC-EI-009 through 013:**

- `SPEC-EI-009` (Evaluation Run Aggregate State Machine) — a real case-level state
  machine (`CaseExecutionStatus`: COMPLETED/FAILED/SKIPPED); a runner exception is now
  caught and recorded as FAILED instead of propagating and blocking the run forever;
  a new `skip_case()` (`POST .../skip`); `finalize_scoring()` now finalizes a run with
  any FAILED/SKIPPED case as PARTIAL instead of COMPARING.
- `SPEC-EI-010` (Run Create Cancel Query Api) — `cancel_run()` is now idempotent
  (a resubmitted cancel against an already-CANCELLED run returns it rather than
  raising); `GET /evaluation/runs?dataset_id=&status=` gives real state visibility
  over every run against a dataset.
- `SPEC-EI-011` (Case Runner Worker Lease Retry) — `create_run()` now enqueues every
  dataset test case into a new claimable work queue
  (`CaseExecutionQueueRepository`, table `evaluation_case_execution_queue`); a new
  `CaseRunnerService`/`CaseRunnerWorker` claims due entries via compare-and-swap,
  retries a FAILED case with exponential backoff up to 5 attempts before marking it
  EXHAUSTED, and reclaims a crashed worker's expired lease. The REST `/execute`/
  `/skip` endpoints remain available as an admin/CI override.
- `SPEC-EI-012` (Agent Runtime Evaluation Client Contract) — a real httpx client,
  `HttpAgentRuntimeEvaluationAdapter`, against 03-agent-runtime-orchestration's own
  evaluation endpoint contract (`Settings.agent_runtime_evaluation_mode`, "fake"
  default keeps every hermetic test's own deterministic simulator).
- `SPEC-EI-013` (Langsmith Experiment Linkage) — real LangSmith Dataset/Experiment SDK
  adapters (`Settings.langsmith_mode`, "noop" default); the outcome is now persisted
  (`LangSmithLinkRepository`) and `EvaluateReleaseGateService` fails a gate closed
  when LangSmith was genuinely enabled but unavailable — never for a deployment that
  simply never opted in.

**Phase 03 (graders and scoring) is complete — SPEC-EI-014 through 018:**

- `SPEC-EI-014`/`SPEC-EI-015` (Deterministic Grader Registry / Safety Policy
  Compliance Graders) — the full 9-name LLD catalog now grades for real, folded onto
  6 actual `EvaluationDimension` slots: `RootCauseMatchGrader`, `PolicyComplianceGrader`
  (zero policy violations + `approval_triggered` matching `required_approval`
  exactly), `ResolutionSuccessGrader` (`final_state` match *and* an independent
  `verification_passed` signal — "Agents Must Not Self-Certify Success" applied at
  grading time), `ToolArgumentSchemaGrader`. `CaseExecutionResult` gained
  `approval_triggered`/`verification_passed`/`tool_call_args`/`explanation_text`.
  `GraderRegistryPort.list_registered()` replaces a hand-maintained static catalog
  that had already drifted from what was actually registered.
- `SPEC-EI-016` (Quality Llm Judge Graders) — `AnthropicQualityJudge`, a real judge
  via the `anthropic` SDK's structured-output `messages.parse()`
  (`Settings.llm_judge_mode`, "placeholder" default keeps the old always-UNSCORED
  adapter). Unlike the Agent Runtime client, the judge prompt deliberately includes
  ground truth — grading against it is the whole job.
- `SPEC-EI-017` (Evaluation Score Persistence) — `ScoreRepository.save_many()`
  commits a whole case's dimension scores in one transaction instead of N separate
  ones, replaying idempotently.
- `SPEC-EI-018` (Judge Calibration Drift Guard) — `EvaluateJudgeCalibrationService`
  grades a caller-supplied calibration set directly against a judge, computes mean
  absolute error, and disables the bundle (`JudgeBundleStatusRepository`) past a
  threshold; `GraderRegistry.grade()` checks that status before ever invoking an
  LLM_JUDGE grader, so a disabled bundle spends no tokens.

**Phase 04 (regression and release gate) is complete — SPEC-EI-019 through 022:**

- `SPEC-EI-019`/`SPEC-EI-020`/`SPEC-EI-021` (Baseline Run Regression Comparator /
  Release Gate Policy Critical Cases / Regression Report Api Event) — found already
  implemented by earlier phase-02/03 work (`CompareRegressionService`,
  `EvaluateReleaseGateService`, the report REST API, and every named event); no code
  changes were needed, only verified as part of this phase's own audit.
- `SPEC-EI-022` (Ci Evaluation Gate Harness) — a genuine gap: nothing let CI drive a
  full benchmark with one command and read a real exit code. New
  `CiEvaluationGateService` composes create_run (idempotent) -> a bounded
  `CaseRunnerPort.run_once()` drive loop -> score every completed case -> finalize ->
  compare -> evaluate-gate, entirely through existing `ports_in` Protocols, into one
  `CiGateOutcome.passed: bool`. A resubmitted run_key against an already-terminal run
  replays the existing report instead of re-driving the pipeline. New CLI
  `uv run evaluation-ci-gate --run-key ... --dataset-id ... --json` (`[project.scripts]`
  entry, `src/evaluationimprovement/ci_gate.py`) exits 0 only when the gate passed.

**Phase 05 (improvement candidate lifecycle) is complete — SPEC-EI-023 through 026:**

- `SPEC-EI-023` (Failure Clustering Root Cause Taxonomy) — a genuine gap: nothing
  grouped a run's own failed scores into a root-cause taxonomy. New
  `ClusterRunFailuresService` derives `(dimension, failure_code)` clusters at query
  time from `ScoreRepository.find_active_by_run()` — no new table, per this spec's
  own persistence design — with a stable `cluster_id = "{dimension}:{failure_code}"`
  string directly reusable as `CreateImprovementCandidateCommand.
  source_failure_cluster_id`. New read endpoint
  `GET /evaluation/runs/{runId}/failure-clusters`.
- `SPEC-EI-024` (Improvement Candidate Aggregate State Machine) — found already
  implemented by earlier phase-00 work (`domain.improvement_candidate.
  ImprovementCandidate`, its own `StateMachine` for both candidate status and the
  Canary sub-state); only the one field SPEC-EI-025 needed was added, no other
  change.
- `SPEC-EI-025` (Candidate Benchmark Binding Gate Enforcement) — a real gap:
  `record_benchmark()` trusted a bare caller-supplied `passed: bool` with no
  verification. `RecordCandidateBenchmarkCommand` now carries `benchmark_run_id:
  RunId` instead; the service looks that run up, requires a terminal PASSED/FAILED
  release-gate decision, and derives `passed` from it directly.
  `ImprovementCandidate.benchmark_run_id` (new field), the Postgres column (new
  migration `f6c3a9e1b7d4`), the REST request/response, and the idempotency-cache
  codec all carry the binding end to end.
- `SPEC-EI-026` (Policy Approval Release Contract) — implements the request side:
  new `HttpPolicyApprovalAdapter` (httpx, gated behind
  `Settings.policy_approval_mode`) against 06-policy-approval-governance's own real
  `POST /api/v1/approval-requests` contract (`approvalType=GENERIC`), tested against
  a mock transport. Consuming 06's own approval granted/denied/expired/cancelled
  events is explicitly out of this phase's scope — see
  `docs/specs/.../SPEC-EI-026-.../traceability-entry.yaml` for why that is
  SPEC-EI-032's (phase-07) own scope instead.

**Phase 06 (canary and controlled release) is complete — SPEC-EI-027 through 029:**

- `SPEC-EI-027` (Canary Plan Rollout State Machine) — found almost entirely already
  implemented by earlier phase-00 work (`CanaryPlan`/`CanaryStage`, the Canary
  sub-state machine, `ManageCanaryService`, optimistic-locking CAS); the one real
  gap was `CanaryStage.sample_size` (this phase's own mandatory constraint names
  four required fields, only three existed) — added, validated positive, threaded
  through the command/JSONB serialization/REST schema.
- `SPEC-EI-028` (Online Sample Evaluation) — a genuine gap: nothing sampled/scored
  production traces before this phase. New `CollectOnlineSampleService` queues an
  already-selected, already-redacted trace (`POST /evaluation/online-samples`) and
  delayed-scores it (`score_pending()`, an operational surface like
  `OutboxDispatchPort`/`CaseRunnerPort`, never REST-exposed) via new
  `PlaceholderOnlineSampleJudge`/`AnthropicOnlineSampleJudge` — the same four
  quality facets `AnthropicQualityJudge` already models, but graded with no ground
  truth (a production trace has none). Consuming the actual upstream events
  (workflow completed / ticket reopened / tool failed / approval denied) and the
  sampling policy that selects a trace are SPEC-EI-030's own scope (phase-07).
- `SPEC-EI-029` (Promotion Criteria Rollback Request) — rollback-request itself was
  already SPEC-EI-027 scope; the real gap was Promotion Criteria, a gap
  `ManageCanaryService.advance()`'s own docstring already named as an unenforced
  caller obligation. New `EvaluateCanaryPromotionService` aggregates a candidate's
  own bound online samples against its current canary stage's `sample_size`/
  `rollback_error_rate_threshold` and returns a pure recommendation — never an
  executed action. New read endpoint
  `GET /evaluation/improvement-candidates/{candidateId}/promotion-criteria`.

**Phase 07 (cross domain contracts) is complete — SPEC-EI-030 through 033:**

- `SPEC-EI-030`/`SPEC-EI-031` (Ticket Runtime Evaluation Contract / Memory Tool
  Evidence Contract) — closes the gap `infrastructure/messaging/rabbitmq_consumer.py`
  marked as future work since SPEC-EI-001: real consumers for `ticket.resolved.v1`/
  `ticket.reopened.v1`/`workflow.completed.v1`/`workflow.failed.v1`/
  `tool.completed.v1`/`memory.retrieval.completed.v1`, field names transcribed from
  each real producer's own actual published payload (several diverge from this
  domain's own illustrative 06-event-contracts sketch — e.g. the real event is
  `workflow.completed.v1`, not `agent.workflow.completed.v1`; `tool.completed.v1` is
  one event type for both success and failure, not two). New
  `ConsumeCrossDomainEventService` redacts every free-text field down to an explicit
  allowlist and funnels into `CollectOnlineSampleService.collect()` (SPEC-EI-028),
  honoring 05's own `redactionStatus` evidence marker rather than re-redacting tool
  evidence a second way. New REST endpoints `POST /internal/evaluation/v1/events/*`
  — a manual/ops trigger until a real RabbitMQ consumer exists, mirroring
  memory-knowledge-service's own event-listener precedent (no Python service in this
  repo wires a real AMQP consumer yet).
- `SPEC-EI-032` (Policy Approval Release Approval Contract) — closes the
  request/consume loop SPEC-EI-026 deferred here: new `ConsumeApprovalDecisionEventService`
  consumes 06's own real `approval.granted.v1`/`approval.denied.v1`, resolves the
  candidate via a new `find_by_approval_request_id()` lookup, and drives
  `approve()`/`reject()` automatically — filtered to `sourceDomain=
  "evaluation-improvement"`, the exact field `HttpPolicyApprovalAdapter` already
  sends.
- `SPEC-EI-033` (Observability Evaluation Signal Contract) — 08-observability-platform
  has no real service anywhere in this repo (a one-line "planned" stub), so this
  spec's real scope is 07's own signal contract compliance, self-verified. Found and
  wired three unwired LLD-named metrics (`evaluation_score`,
  `evaluation_cost_tokens_total`, `evaluation_latency_seconds`) onto real per-case
  data, added all seven "关键 span" this domain's own LLD names (no application code
  created a single span before this spec, despite the TracerProvider being
  configured since SPEC-EI-001), and two structured `logger.info()` lines carrying
  every required field this domain's own 12-observability doc names.

**Phase 08 (security, observability, recovery) is complete — SPEC-EI-034/035:**

- `SPEC-EI-034` (Evaluation Security Redaction Observability) — RBAC, redaction, and
  metrics/logs/traces were already real from earlier phases; the genuine gap was
  11-security's own "case-level evidence 需要更高权限" — `AuthorizationPort.
  can_view_sensitive_evidence()` was defined since SPEC-EI-001 but never called, and
  `ScoreView` never even carried evidence to gate. `ScoreView` now carries
  `evidence_ref`/`details`; `CreateRunService.find_scores()` strips both for any
  caller without that permission and audits only the calls that actually see
  evidence. New `optional_actor()` dependency keeps `GET /evaluation/runs/{runId}/
  scores` working for an unidentified caller (the default read floor) while still
  resolving a real role when one is asserted.
- `SPEC-EI-035` (LangSmith/Grader/Outbox Failure Recovery) — LangSmith outage,
  grader error, and partial run recovery were already real. Three closed gaps: (1)
  poison event — new `PoisonEventRepository`; `ConsumeApprovalDecisionEventService`
  now catches a late/conflicting approval decision, records it, and returns `422
  POISON_EVENT` rather than either silently applying it or looping forever; (2)
  admin repair/replay — new `GET /evaluation/poison-events` (visibility) and `POST
  /evaluation/outbox/dispatch` (an audited manual-replay wrapper the plain,
  actor-less `OutboxDispatchPort` itself deliberately stays without); (3) recovery
  scanner — new `GET /evaluation/runs/stuck?sla_seconds=`, the query behind
  12-observability's own "run stuck in RUNNING 或 SCORING beyond SLA" alert that had
  none before.

**Phase 09 (final verification and release) is complete — SPEC-EI-036, the final spec
of domain 07:**

- `SPEC-EI-036` (Evaluation Contract E2E Harness Final Release) — the final coverage
  audit found two genuine gaps neither prior phase's tests had caught: (1) Canary
  lifecycle had no REST endpoints at all — `advance()`/`pause()`/`complete_rollback()`/
  `promote()` were real, tested application-layer methods since earlier phases, but
  `interfaces/rest/router.py` never exposed them, so a candidate approved and
  canary-started over HTTP could never actually reach `PROMOTED` through the API. New
  `POST /improvement-candidates/{id}/advance-canary`, `pause-canary`,
  `complete-rollback`, `promote`. (2) A `PROMOTED` candidate had no application-layer
  rollback path — the Canary sub-state machine's `SUCCEEDED` status is terminal, and
  `request_rollback()` only accepts `ACTIVE`/`EXPANDING`/`PAUSED`/`FAILED`, yet the
  domain aggregate's own `ImprovementCandidate.rollback()` has always supported
  `PROMOTED -> ROLLED_BACK` directly. New `RollbackPromotedCandidateCommand` +
  `ManageCanaryService.rollback_promoted()` + `POST /improvement-candidates/{id}/
  rollback-promoted` — consistent with "07 only requests rollback, Runtime/Config
  owner executes it," it only publishes `ImprovementRollbackRequested` and audits the
  request. All 36 `SPEC-EI-001`..`036` traceability entries are now
  `status: implemented` — **domain 07 (Evaluation Improvement) implementation is
  complete.** See `docs/specs/domains/07-evaluation-improvement/SPEC-EI-036-.../
  release-readiness_CN.md` for the full release checklist and an honest residual-risk
  register (R1–R7) of every deliberately-deferred item across the whole domain (no
  real RabbitMQ AMQP consumer/publisher yet, no real upstream producer yet for
  `memory.retrieval.completed.v1`, LLM Judge is quality-only by design, etc.).

## Stack

Python 3.12+, FastAPI, Pydantic, SQLAlchemy, Alembic, PostgreSQL, httpx, LangSmith SDK,
Anthropic SDK, pytest, import-linter, testcontainers. (Real RabbitMQ wiring for
`EventPublisherPort`/`EventConsumerPort` remains a deliberately deferred adapter-layer
swap, matching every other Python service in this repo — see
`infrastructure/messaging/rabbitmq_publisher.py`/`rabbitmq_consumer.py`'s own
docstrings and `release-readiness_CN.md` R1/R2.)

## Layout

```text
src/evaluationimprovement/
  domain/            # aggregates, value objects, state machines, domain events
  application/        # commands/views/ports, the 10 named use-case services
    services/
  infrastructure/      # persistence (in-memory + Postgres), graders, real + fake clients
    persistence/postgres/  # SQLAlchemy models, repositories, session, migrations
    runtime/               # AgentRuntimeEvaluationPort adapters + CaseRunnerWorker
    langsmith/              # LangSmithPort adapters (no-op + real SDK)
    graders/                 # deterministic + LLM Judge (placeholder + real Anthropic)
    policy/                   # PolicyApprovalPort adapters (fake + real 06 http client)
  interfaces/          # REST + admin + cross-domain event FastAPI routers, DTO mapping, RBAC gate
  container.py         # composition root
  main.py               # FastAPI app factory
migrations/            # Alembic — eleven hand-written revisions (baseline schema,
                       #  outbox/audit tables, dataset content_hash, dataset
                       #  tenant_id, case_execution_result status, case execution
                       #  queue + langsmith run link, case execution grading fields,
                       #  judge bundle status, improvement candidate benchmark_run_id,
                       #  online evaluation samples, poison events)
tests/
  domain/               # aggregate + state-machine unit tests
  application/           # walking-skeleton pipeline tests against in-memory adapters
  infrastructure/         # HttpAgentRuntimeEvaluationAdapter, LangSmith/grader/judge tests
  integration/            # real Postgres via testcontainers (marker: integration)
  architecture/           # import-linter + layer-boundary + no-production-mutation
  contracts/               # event-envelope shape + INV-EI-003 safety-gate contract +
                           #  12-observability metrics/logs/traces signal contract
  test_app.py               # end-to-end HTTP smoke test
  test_container_wiring.py  # Settings-driven adapter selection (fake/http, noop/sdk)
  test_ci_gate_cli.py        # evaluation-ci-gate CLI: argv parsing + exit-code contract
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
