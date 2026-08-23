# tool-integration-gateway

OpsMind domain `05-tool-integration-gateway` — the only entry point through
which Agent Runtime tool intent becomes controlled, approvable, auditable,
idempotent, recoverable external tool execution
(`docs/implementation-plans/domains/05-tool-integration-gateway/00-implementation-roadmap_EN.md`).

## Status

**All phases are complete** — `SPEC-TG-001` through `SPEC-TG-032` are all
`status: implemented`. This closes the entire `05-tool-integration-gateway`
domain roadmap; no further phases remain.

- **SPEC-TG-001 — Module And Package Boundaries**: service skeleton, layered
  package boundaries (`domain -> ports -> application -> adapters`, with
  `api`/`workers` reaching adapters only through `tool_gateway.container`), and
  the full `ToolRequest`/`ToolExecution`/`ToolConnector`/`ToolResultEnvelope`
  state machines.
- **SPEC-TG-002 — Schema Baseline**: a real PostgreSQL `tool` schema (all eight
  07-data-model tables) via SQLAlchemy + Alembic, with Postgres-backed
  repositories selectable alongside SPEC-TG-001's in-memory ones.
- **SPEC-TG-003 — Outbox/Processed-Event/Audit Baseline**: a real RabbitMQ
  outbox publisher, plus the processed-event dedup and audit-record mechanisms
  later phases' event consumers will call.
- **SPEC-TG-004 — Tool Request Aggregate State Machine**: closed the one real
  gap found auditing SPEC-TG-001's own coverage — INV-TG-010 (timeout/partial-
  side-effect/failure stay distinguishable) is now exercised through the real
  application service, not just the domain layer.
- **SPEC-TG-005 — Runtime Tool Request API**: a structured Error Model
  (`api/errors.py`, correlationId + auditable code on every error), the
  `requiresApproval`/`approvalRequestId` response fields, idempotent cancel
  (`POST /tool-requests/{id}/cancel` requires `idempotencyKey`; a repeat call
  is a no-op), and the Result API rekeyed to
  `GET /tool-results/{resultEnvelopeId}` per 05-api-contracts.
- **SPEC-TG-006 — Connector Capability Registry**: INV-TG-008 schema-version
  binding (`ToolRequest.bind_connector()` — also a real correctness fix:
  execution now reuses the connector bound at intake instead of re-resolving
  by capability name, and re-checks it is still schedulable), plus
  `PATCH /connectors/{id}/status` and `GET /capabilities`.
- **SPEC-TG-007 — Policy Risk Decision Integration**: a real POLICY_DENIED
  verdict path (`RiskDecisionRef.denied`, a `StaticPolicyAdapter` hard-deny
  rule) publishing a final `tool.completed.v1`; `tool.request.rejected.v1`
  publication on rejection; a `policy.rule.changed.v1` consumer skeleton.
- **SPEC-TG-008 — Approval Required Linkage**: `tool.approval.required.v1` and
  a denial-side `tool.completed.v1` (status `APPROVAL_DENIED`) are now
  actually published — closed a real Postgres round-trip bug along the way
  (`approval_ref` used to always come back `None` after a reload).
- **SPEC-TG-009 — Approval Decision Event Consumer**: `POST
  /internal/tool-gateway/v1/events/{approval-granted,approval-denied,
  policy-rule-changed}` — event-id dedup, an idempotent skip once a
  ToolRequest has moved past `WAITING_APPROVAL`, and an approval-linkage
  mismatch guard (403 `APPROVAL_LINKAGE_MISMATCH`) that only works because of
  SPEC-TG-008's own Postgres fix.
- **SPEC-TG-010 — Execution Scheduling Worker Lease**: a real lease-expiry
  reclaim scan (`ToolExecutionRepository.find_lease_expired()` +
  `ReclaimExpiredLeasesService`) — a worker that died mid-attempt used to
  leave its `ToolExecution` stuck `CLAIMED`/`PREPARING`/`INVOKING` forever.
  `ExecutionWorker.run_forever()` now runs this pass before every claim batch.
- **SPEC-TG-011 — Connector SDK And Built-In Fake Connector**: promoted the
  former private `_FixedOutcomeConnector` test double into a real, shared
  `adapters/connectors/builtin/fake_connector.py`, plus a reusable connector
  contract test suite (`tests/contracts/connector_contract.py`).
- **SPEC-TG-012 — Credential Binding Invocation Preparation**: real
  `CredentialBinding` persistence — the `credential_bindings` table had zero
  writers since SPEC-TG-002; `InMemoryVaultCredentialAdapter` now resolves and
  reuses an `ACTIVE` binding per `(connector, scope)` instead of minting a
  fresh reference on every invocation.
- **SPEC-TG-013 — Operation Key Side Effect Guard**: fixed the mutation
  operation-key format to the full
  `toolRequestId:attemptNumber:connectorId:capabilityName` 09-concurrency-
  and-idempotency recommends (it was missing the last two segments).
- **SPEC-TG-014 — Result Envelope Normalization Redaction**: closed a real
  security gap — only `summary` was ever redacted; a connector's
  `structured_output` reached events/API completely unredacted. Added a
  recursive `redact_structured_output()` walker and broadened the regex
  redaction patterns (PEM private keys, email addresses, RFC 1918 internal
  IPv4).
- **SPEC-TG-015 — Tool Completed Event Publication**: the `tool.completed.v1`
  SUCCESS payload now carries every 06-event-contracts field (ticket/workflow/
  task refs, connectorId, structuredOutput, resultEnvelopeId, evidenceRefs,
  redactionStatus, errorCode, retryable) — it previously only carried four.
- **SPEC-TG-016 — Retry Policy And Retry Scheduling**: a retryable connector
  failure now actually retries — `ToolRequest.retry()`/`.terminal_fail()`
  existed unused since SPEC-TG-001. Backoff (`RetryPolicy.backoff_seconds`)
  is real: a new `retry_not_before` column excludes a backing-off request
  from `find_queued()`. Publishes `tool.execution.retry_scheduled.v1` on
  retry, a full `tool.completed.v1` (`TERMINAL_FAILED`) once attempts are
  exhausted.
- **SPEC-TG-017 — Timeout Partial Side Effect Reconciliation**: reconciliation's
  own confirmed-failure branch now shares SPEC-TG-016's retry-vs-terminal
  decision (it used to always jump straight to `TERMINAL_FAILED`, ignoring
  "if retry is allowed") and now publishes a final event either way (it
  previously published nothing). Wired the pre-existing, untested
  `ReconciliationWorker`.
- **SPEC-TG-018 — Tool Request Cancellation**: closed a real concurrency gap —
  cancelling an executing request used to auto-confirm `CANCELLED`
  immediately (a SPEC-TG-001-era simplification its own docstring named this
  spec to fix). Now genuinely stops at `CANCEL_REQUESTED`; a new
  `cancellation_race.save_resolved_tool_request()` resolves the race with
  whichever resolver (`execute_tool_request`/`reconcile_execution`) settles
  the attempt. Cancelling an already-final request is now a no-op returning
  the final fact instead of raising.
- **SPEC-TG-019 — Connector Health And Degraded Control**: a `DEGRADED`
  connector can now serve as an eligible read-only/low-risk fallback (it was
  fully unschedulable before). Wired the pre-existing, untested
  `ConnectorHealthWorker` through a new `apply_health_check_result()`
  application method (it used to mutate the registry directly, bypassing
  audit/outbox). Both automatic and admin-driven health transitions now
  publish `tool.connector.health_changed.v1`.
- **SPEC-TG-020 — Secret Isolation And Raw Output Access**: real controlled
  raw-output storage (`RawOutputStorePort`) plus the privileged
  `GET /tool-results/{id}/raw` endpoint — `raw_output_ref` existed since
  SPEC-TG-001 but was always `None`; this endpoint itself didn't exist at
  all. Gated to `HUMAN_OPERATOR` + a mandatory audit reason (no real RBAC
  system exists in this platform); every attempt, granted or denied, is
  audited.
- **SPEC-TG-021 — Authorization Scope And Network Policy**: a connector can
  now restrict which requester types (`AGENT`/`SYSTEM`/`HUMAN_OPERATOR`) may
  invoke it (`ToolConnector.allowed_requester_types`), enforced at intake and
  rejected the same way an unregistered capability already was. Added
  `ToolConnector.is_host_allowed()`, the "undeclared endpoints are denied by
  default" enforcement primitive a future real network-calling connector is
  expected to call. Tenant/ticket-scope/workflow-purpose authorization stay
  deferred — no such concept exists anywhere in this platform's domain model
  yet.
- **SPEC-TG-022 — 03 Agent Runtime Tool Contract**: a real `workflow.cancelled.v1`
  consumer — named in this domain's own event contracts since SPEC-TG-001 but
  never implemented at all. New `POST /internal/tool-gateway/v1/events/
  workflow-cancelled` finds every non-terminal Tool Request for a cancelled
  workflow instance and attempts to cancel each. New
  `tests/contracts/test_cross_domain_contracts.py` — the home for this and
  the sibling TG-023~025 cross-domain assertions.
- **SPEC-TG-023 — 06 Policy Approval Contract**: no domain-06 service exists
  in this monorepo (only a design-doc placeholder) — verified this domain's
  own `tool.approval.required.v1`/approval-decision shapes are internally
  complete instead of against a live consumer.
- **SPEC-TG-024 — 04 Memory Evidence Contract**: `evidenceRefs` (on
  `tool.completed.v1` since SPEC-TG-001) is now actually populated — the
  controlled `raw_output_ref` itself, when a connector produces raw output,
  never its content.
- **SPEC-TG-025 — 02 Ticket Workflow Traceability Contract**: found that
  `ticket-workflow-service` has real, tested consumers expecting a completely
  different `tool.execution.*` event family/vocabulary this service has never
  spoken (predating Agent Runtime Orchestration's introduction as the
  mediating domain — treated as legacy, not bridged; see this spec's own
  traceability entry for the full reasoning). Closed the one concrete gap
  found instead: `tool.request.rejected.v1` never carried ticket/workflow
  context at all — now matches `tool.completed.v1`'s own field set.

- **SPEC-TG-026 — Metrics Logs Traces**: real OpenTelemetry wiring
  (`adapters/observability/otel_setup.py`, "console" exporter as the
  genuinely-functional safe default, "otlp" as explicit opt-in) plus
  `application/telemetry.py::ToolGatewayTelemetry` — the 12 vendor-neutral
  instruments 12-observability names verbatim, threaded through every
  application service that owns a relevant transition. Also turned "redaction
  failure must prevent publishing raw content" into real behavior: a
  `redact()`/`redact_structured_output()` exception in `execute_tool_request`
  or `reconcile_execution` now records the failure metric and re-raises
  instead of silently letting raw output through.
- **SPEC-TG-027 — Audit Query And Admin Reporting**: closed another instance
  of this engagement's recurring "real DB column, no domain field" finding —
  `tool_audit_records.tool_request_id`/`execution_id`/`connector_id` have been
  real columns since SPEC-TG-002 but were always written `NULL`. New
  `AuditQueryService` + `GET /internal/tool-gateway/v1/admin/audit`
  (`ticket_id`/`actor_id`/`connector_id`, at most one filter per call);
  `find_by_ticket_id` is the first query in this service to use the real
  Postgres JSONB `.astext` comparator (`ticket_id` lives in `metadata_json`,
  not a dedicated column).
- **SPEC-TG-028 — Outbox Poison Replay Admin Repair**: `find_dead_letter()`
  was implemented on both `OutboxRepository` adapters since SPEC-TG-002/003
  but never declared on the port itself — another instance of the "adapter
  has it, port doesn't" pattern. New `AdminOutboxService.replay()` (404/409
  guards, mandatory `outbox_event_replayed` audit record) +
  `GET /internal/tool-gateway/v1/admin/outbox/dead-letter` /
  `POST /internal/tool-gateway/v1/admin/outbox/{outboxId}/replay`.
- **SPEC-TG-029 — Connector Admin Lifecycle API**: `ConnectorView` gained 9
  manifest fields (network/timeout/retry policy, secret requirements,
  allowed requester types) it never exposed, plus
  `GET /internal/tool-gateway/v1/connectors/{connector_id}`. Reuses the
  pre-existing `ConnectorNotFoundException` -> 503 `CONNECTOR_UNAVAILABLE`
  mapping for an unknown id rather than inventing a second not-found meaning
  for the same `connector_id` concept.

- **SPEC-TG-030 — Crash Recovery Backpressure Scaling**: closed two concrete
  gaps in 10-failure-handling's own "Connector Crash Or Unavailability"
  section. A QUEUED request whose bound connector had since gone unavailable
  used to raise an exception `ExecutionWorker.run_once()` only logs — it now
  either reselects a fallback (READ_ONLY capabilities only, onto another
  already-eligible connector) or reaches `TERMINAL_FAILED` with a published
  `tool.completed.v1` and an audit trail; a MUTATING capability never
  auto-reselects ("high-risk mutation must not switch connectors
  automatically unless policy allows it" — no such policy hook exists).
  `apply_health_check_result` gained the automatic DEGRADED -> DISABLED half
  ("health check failures beyond threshold move it to DISABLED") it never
  had — a connector already DEGRADED that kept failing every check used to
  be silently ignored forever. New `GatewayRecoveryService.run_recovery()`
  (outbox replay + lease-expiry reclaim together) is exposed as
  `POST /internal/tool-gateway/v1/admin/recovery/run` rather than run
  automatically at process boot — see `application/gateway_recovery.py`'s own
  module docstring for why an eager call in `main.create_app()` would make
  every hermetic test's mere import require a live database connection.
- **SPEC-TG-031 — Contract E2E Harness**: audited 14-testing-strategy's own
  test-goal lists item by item; almost everything was already covered by a
  prior phase's own tests. Closed two real harness gaps: an HTTP-level
  regression guard proving neither `GET /tool-requests/{id}` nor
  `GET /tool-results/{id}` ever contains a real `vault://` credential
  reference end to end, and a proof that a worker double-claim race can never
  publish a second `tool.completed.v1` for an already-COMPLETED request
  (`InvalidToolRequestTransitionException` fires before the outbox-append
  lines are ever reached).
- **SPEC-TG-032 — Final Coverage Audit Release Readiness**: found the one
  remaining real gap in 10-failure-handling's own Reconciliation section —
  `ResultStatus.UNCERTAIN` (a real enum member since SPEC-TG-001) fell into
  the same branch as a confirmed FAILED outcome, eligible for the same
  automatic retry a genuinely unknown outcome must never get. Now a distinct
  branch: never retried, reaches `TERMINAL_FAILED` with its own audit action,
  and the published `tool.completed.v1` reads `status: "UNCERTAIN"` rather
  than being collapsed into a generic failure. Also verified this service's
  own `migrations/env.py` already isolates its `alembic_version` table under
  the `tool` schema — the exact cross-service collision class
  memory-knowledge-service's own final-verification phase found in a sibling
  domain does not apply here, and was already guarded against from an
  earlier phase.

**This closes the entire `05-tool-integration-gateway` domain roadmap** — all
32 `SPEC-TG-0xx` specs are now `status: implemented`, no further phases
remain. The real RabbitMQ subscription loop for consumed events
(`adapters/events/rabbitmq_consumer.py`) stays deferred until a real
publisher exists on the other end, as does actually starting
`ExecutionWorker`/`OutboxWorker`/`ReconciliationWorker`/
`ConnectorHealthWorker` as a running process — no file in this repo
constructs any of them outside of tests. "All execution attempts by
workflow" and "execution results by approval request" (12-observability's
own audit-query list) stay deferred — no `workflow_instance_id`/
`approval_request_id` column exists on `tool_audit_records`.

## Tech stack

Python 3.13 / FastAPI / SQLAlchemy 2.x / Alembic / pika — this domain's own
`13-package-and-class-design` LLD gives a concrete Python package tree
(`src/tool_gateway/{api,application,domain,ports,adapters,workers}`, `.py`
filenames throughout), which this implementation follows literally. See
`docs/low-level-design/shared/technology-baseline/README_EN.md` §3 for the
platform-wide Java/Python service boundary; this domain's own LLD is the more
specific, more recent source of truth for this one service.

## Layout

```text
src/tool_gateway/
  domain/        pure business rules — no framework dependency
  ports/         typing.Protocol interfaces (application depends on these)
  application/   use-case orchestration — depends on domain + ports only
  adapters/      Protocol implementations (in-memory + real Postgres/RabbitMQ)
  api/           FastAPI routers — reach adapters only via container
  workers/       poll loops — reach adapters only via container
  container.py   composition root (the only module wiring adapters)
  main.py        FastAPI app entry point
migrations/      Alembic migrations for the `tool` Postgres schema
tests/
  domain/         state-machine and aggregate-rule unit tests
  application/     walking-skeleton tests against the wired container (in-memory)
  adapters/        built-in connector adapter tests (Echo/Fake)
  contracts/       reusable ConnectorPort contract assertions + cross-domain
                    contract tests against sibling domains' own frozen LLDs
  architecture/    import-linter + AST layer-boundary tests
  integration/     real Postgres/RabbitMQ tests via testcontainers
  test_app.py      end-to-end tests through the real HTTP app (in-memory)
```

`adapters/raw_output/` (SPEC-TG-020) holds the controlled raw-output storage
placeholder alongside `adapters/{credentials,redaction,connectors}/`.
`adapters/observability/` (SPEC-TG-026) holds the OpenTelemetry SDK-wiring
module (`otel_setup.py`); the vendor-neutral instrument names it feeds live in
`application/telemetry.py`, not under `adapters/`, matching the same
domain/application-owns-the-names, adapter-owns-the-wiring split MK/ARO's own
telemetry already established.
`application/gateway_recovery.py` (SPEC-TG-030) is admin-triggered via
`POST /internal/tool-gateway/v1/admin/recovery/run`, not called from
`main.create_app()` — see that module's own docstring.

## Run

```bash
uv sync
uv run pytest -q -m "not integration"   # 138 passed, fast/hermetic (in-memory adapters)
uv run pytest -q -m integration         # 27 passed, needs Docker (real Postgres/RabbitMQ)
uv run pyflakes src tests               # 0 warnings

# Apply the tool schema migration against the shared local Postgres
# (infrastructure/docker-compose/local-platform.yml):
DB_HOST=localhost DB_PORT=5432 DB_NAME=ticket_workflow \
  DB_USERNAME=ticket_workflow DB_PASSWORD=ticket_workflow uv run alembic upgrade head

uv run python -m tool_gateway.main      # serves on :8020 (tool_gateway_persistence=postgres by default)
```
