# SPEC-XOBS-001 — approval notifications, trace coverage, cross-service contract tests

Status: implemented 2026-09-09
Owner: platform / cross-cutting (no single domain)
Related: [[opsmind-architecture-gaps]] P2, [[langsmith-and-tracing-wired]], SPEC-XREL-001

Three independent P2 quality/observability gaps, done together because they share a
theme (make the platform's own behaviour visible) and a test-infra footprint.

---

## Part A — real approval notifications (was `NoOpApprovalNotificationAdapter`)

### A.1 Problem

`policy-approval-governance-service` calls `ApprovalNotificationPort.notifyRequested()`
on every new approval request and `notifyDecided()` on every grant / deny / cancel.
The only implementation was `NoOpApprovalNotificationAdapter` — a `@Component` that
logs at DEBUG and does nothing else. So on the live platform an approval request
appears with **no signal to any human**: the approver never learns a decision is
waiting, and the requester (an agent-runtime workflow's owner, a tool-gateway
execution) never learns the outcome except by polling. Mailpit (SMTP `:1025`, web
`:8025`) has been a running container since the Keycloak-SMTP work but nothing in the
business services used it.

### A.2 Design

New `SmtpApprovalNotificationAdapter implements ApprovalNotificationPort`
(`infrastructure/notification/`), using Spring's `JavaMailSender`
(`spring-boot-starter-mail`), pointed at Mailpit in compose.

- **Selection is a property, not a code swap.** `opsmind.governance.notifications.mode`:
  - `noop` (default, `matchIfMissing = true`) → `NoOpApprovalNotificationAdapter`, so
    every existing `@SpringBootTest` and every deployment that has not opted in is
    byte-for-byte unchanged.
  - `smtp` → `SmtpApprovalNotificationAdapter`. `full-platform.yml` sets this.
  - Each adapter carries `@ConditionalOnProperty` for its own value — exactly one bean
    exists, no `@Primary`, no ambiguity.
- **Best-effort, never throws.** The port's own javadoc says notification is
  "out-of-band of the governance fact stream". A `MailException` (Mailpit down, bad
  address) is caught and logged at WARN; the approval transaction that triggered it has
  already committed and must not be rolled back by a mail failure. This matches
  `user-access-authentication-service`'s own "keep local revocation, retry the IdP
  notification later" best-effort stance.
- **Addressing.** Governance holds no user directory — an `ApprovalRequest` carries
  `requestedBy` (a subject id or username) and `sourceDomain`, no email addresses. So:
  - `notifyRequested` → a single configured approver mailbox
    `opsmind.governance.notifications.approver-address`
    (default `it-approvers@opsmind.dev`). Subject encodes risk + type + ticket;
    body carries `approvalRequestId`, `sourceDomain/sourceRequestId`, `riskLevel`,
    `expiresAt`, and the constraint list.
  - `notifyDecided` → the requester, resolved from `requestedBy`: if it already
    contains `@`, used verbatim; else `{requestedBy}@{opsmind.governance.notifications.email-domain}`
    (default `opsmind.dev`, which is what the seed users use — `test.agent@opsmind.dev`).
    Subject encodes the terminal `status`; body carries the same identifiers.
  - `from` = `opsmind.governance.notifications.from-address` (default
    `it-governance@opsmind.dev`).
- `management.health.mail.enabled: false` in `application.yml` so the mail starter
  being on the classpath does not make `/actuator/health` flap in the `noop` default.

### A.3 Files

```
services/policy-approval-governance-service/pom.xml                                    (+ spring-boot-starter-mail)
.../src/main/java/.../infrastructure/notification/SmtpApprovalNotificationAdapter.java (new)
.../src/main/java/.../infrastructure/notification/NoOpApprovalNotificationAdapter.java (+ @ConditionalOnProperty noop)
.../src/main/java/.../config/GovernanceNotificationProperties.java                     (new, @ConfigurationProperties)
.../src/main/resources/application.yml                                                 (+ opsmind.governance.notifications.*, management.health.mail.enabled:false)
.../src/main/resources/application-local.yml                                           (+ spring.mail.* pointing at ${MAIL_HOST:localhost}:${MAIL_PORT:1025})
.../src/test/java/.../infrastructure/notification/SmtpApprovalNotificationAdapterTest.java (new — Mockito JavaMailSender)
infrastructure/docker-compose/full-platform.yml                                        (governance: + GOVERNANCE_NOTIFICATIONS_MODE=smtp, MAIL_HOST=mailpit, MAIL_PORT=1025; + depends_on mailpit)
```

### A.4 Verification

`SmtpApprovalNotificationAdapterTest` asserts: a `SimpleMailMessage` is sent to the
approver address on `notifyRequested`; to the derived requester address on
`notifyDecided`; a thrown `MailException` is swallowed (no exception propagates).
Live: bring the stack up, drive `scripts/seed-governance-policies.sh` /
`approval-loop-smoke.sh`, read the mail at `http://localhost:8025`.

---

## Part B — distributed-trace coverage for the last two services + per-request spans

### B.1 Problem (corrected from the memory note)

The memory note said "tool-gateway + attachment no OTel (no spans)". On inspection:

- **`attachment-service`** already has `micrometer-tracing-bridge-otel` +
  `opentelemetry-exporter-otlp` + `spring-boot-starter-actuator` in its pom **and** the
  `management.otlp.tracing.endpoint` block in `application-local.yml`. The *only* gap
  is that `full-platform.yml` never set `OTEL_EXPORTER_OTLP_ENDPOINT` /
  `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` / `OTEL_RESOURCE_ATTRIBUTES` for it, so it
  emitted at the 0.1 default sampling to `localhost:4318` (nothing). → compose env only.
- **`tool-integration-gateway`** (Python) already has the OTel SDK deps + a wired
  `adapters/observability/otel_setup.py` (called from `main.py`). Gaps: (a) no
  `OTEL_*` env in compose → `otel_exporter` defaults to `console`; (b) **no**
  `opentelemetry-instrumentation-fastapi` → only hand-written domain spans, no
  per-HTTP-request server span, so "employee → agent-runtime → tool-gateway →
  Keycloak" shows a gap where the gateway's request handling should be.
- **No Python FastAPI service** (`agent-runtime`, `memory-knowledge`,
  `tool-integration-gateway`, `evaluation-improvement`) has
  `opentelemetry-instrumentation-fastapi`. All four only produce manual domain spans.

### B.2 Design

- **`opentelemetry-instrumentation-fastapi`** added to all four Python services'
  `pyproject.toml` + `uv.lock`, and `FastAPIInstrumentor.instrument_app(app)` called
  in each `create_app()` right after `configure_observability(settings)`. This adds one
  parent `<METHOD> <route>` SERVER span per request that the existing domain spans
  nest under, and propagates / continues incoming `traceparent` — so a call arriving
  from agent-runtime with a trace context now continues the same trace instead of
  starting a detached one. Guarded: the instrumentor is imported defensively; a missing
  package logs a warning and the app still starts (same "degrade, don't crash" stance
  as the LLM-SDK imports).
- **`tool-integration-gateway`** + **`attachment-service`** get the same `OTEL_*` /
  `MANAGEMENT_TRACING_*` compose env every other traced service already has, with a
  `service.namespace` that matches the Tempo multi-tenant routing
  (`tool-integration` / — attachment has no dedicated Tempo tenant, routes to
  `shared`).
- `otel_service_name` defaults are already correct in each service's `settings.py`.

### B.3 Files

```
services/agent-runtime-service/pyproject.toml + uv.lock       (+ opentelemetry-instrumentation-fastapi)
services/agent-runtime-service/src/agentruntime/main.py       (+ FastAPIInstrumentor.instrument_app)
services/memory-knowledge-service/pyproject.toml + uv.lock    (+ dep)
services/memory-knowledge-service/src/.../main.py             (+ instrument_app)
services/tool-integration-gateway/pyproject.toml + uv.lock    (+ dep)
services/tool-integration-gateway/src/tool_gateway/main.py    (+ instrument_app)
services/evaluation-improvement-service/pyproject.toml + uv.lock (+ dep)
services/evaluation-improvement-service/src/.../main.py       (+ instrument_app)
services/event-relay/...                                       (relay is a pure consumer, no FastAPI — skipped)
infrastructure/docker-compose/full-platform.yml              (attachment + tool-integration-gateway: + OTEL_* env; attachment + depends_on unchanged)
```

### B.4 Verification

Each service's `test_app.py` / equivalent gets one assertion that a `TestClient` GET
of `/health` produces a span whose name starts with the HTTP method (via an in-memory
`InMemorySpanExporter` set on the provider). Live: after `up -d`, a conversation that
reaches a tool now renders a single connected waterfall across `agent-runtime` →
`tool-integration` → (Keycloak Admin call) in Grafana/Tempo.

---

## Part C — cross-service contract tests

### C.1 Problem

Every cross-service wire contract in this platform is **hand-mirrored**: the producer
builds a dict/DTO, the consumer re-declares a matching schema, and nothing checks the
two agree until runtime. Two real bugs this session came from exactly that:

- `verificationCondition` — eval-improvement's `EvaluationTestCase.verification_condition`
  is `dict[str, Any]`; agent-runtime's `ExecuteEvaluationCaseRequest` first declared it
  `str`. A `{}` from eval → 400 at agent-runtime. Found only by running the pipeline.
- `side_effect_kind` — tool-gateway's `PostgresConnectorRepository` never persisted it;
  it was re-derived from a capability-name keyword heuristic on read, silently making
  every `identity.user.*` capability `READ_ONLY` → null `operationKey` → every real
  execution `UNSUPPORTED_CAPABILITY`. Found only by executing a real connector.

### C.2 Design — consumer-driven contract fixtures

A new top-level **`contracts/`** directory holds the single source of truth for each
hand-mirrored contract as a committed JSON fixture (a real example message / DTO), plus
a `README.md` explaining the pattern. No new framework, no broker, no Pact: each
service already has a test runner, so each **consumer and producer adds a test that
loads the shared fixture and runs it through its own real (de)serializer**, asserting
the shape it expects is the shape in the fixture. When one side changes the contract,
its test forces the fixture to change, and the *other* side's test then fails in that
side's own CI — the drift is caught at change time, not at runtime.

Fixtures (each with a `.json` payload + a short `.md` note naming producer, consumers,
and the field contract):

| fixture | producer | consumers |
|---|---|---|
| `governance-approval-granted-v1.json` | policy-approval-governance `ApprovalGrantedEvent` | event-relay → agent-runtime, tool-integration-gateway |
| `governance-approval-denied-v1.json` | policy-approval-governance `ApprovalDeniedEvent` | event-relay → agent-runtime, tool-integration-gateway |
| `governance-approval-expired-v1.json` | policy-approval-governance `ApprovalExpiredEvent` | event-relay → agent-runtime |
| `agent-runtime-runtime-event-request.json` | event-relay | agent-runtime `POST /internal/agent-runtime/v1/events` (`RuntimeEventRequest`) |
| `agent-runtime-execute-evaluation-case-request.json` | eval-improvement `HttpAgentRuntimeEvaluationAdapter` | agent-runtime `ExecuteEvaluationCaseRequest` (**`verificationCondition` is an object**) |
| `agent-runtime-execute-evaluation-case-response.json` | agent-runtime | eval-improvement `HttpAgentRuntimeEvaluationAdapter` (`promptTokens`/`completionTokens` present) |
| `improvement-promoted-v1.json` | eval-improvement `CreateImprovementCandidateService` | event-relay → agent-runtime `ImprovementPromotedEventRequest` |
| `tool-gateway-submit-tool-request.json` | agent-runtime `HttpToolGatewayPort` | tool-integration-gateway `POST /internal/tool-gateway/v1/tool-requests` |
| `tool-gateway-execute-tool-request-response.json` | tool-integration-gateway sync `/execute` | agent-runtime `HttpToolGatewayPort` |
| `tool-gateway-connector-manifest-roundtrip.json` | — | tool-gateway `PostgresConnectorRepository` `_connector_to_row_values` / `_row_to_connector` must round-trip `sideEffectKind` (guards the fixed bug) |

### C.3 Tests

```
contracts/README.md                                            (new — the pattern + fixture index)
contracts/*.json                                               (new — the fixtures above)
contracts/*.md                                                 (new — per-fixture field contract notes)
services/event-relay/tests/test_contracts.py                   (new — governance fixtures -> parse_envelope + plan_deliveries -> assert the produced bodies match the agent-runtime / tool-gateway fixtures)
services/agent-runtime-service/tests/contracts/test_cross_service_wire_contracts.py       (new — RuntimeEventRequest / ExecuteEvaluationCaseRequest / ExecuteEvaluationCaseResponse accept & round-trip the fixtures; verificationCondition-as-object pinned)
services/evaluation-improvement-service/tests/contracts/test_cross_service_wire_contracts.py (new — the execute-case client parses the response fixture; improvement.promoted payload keys)
services/tool-integration-gateway/tests/contracts/test_cross_service_wire_contracts.py    (new — ApprovalGrantedEventRequest / submit / execute-response fixtures; connector manifest sideEffectKind round-trip)
```

Each test computes the repo root with `Path(__file__).resolve().parents[N]` and reads
`contracts/`. A missing fixture fails loudly (no silent skip).

### C.4 What this does and does not buy

Catches: a field renamed / retyped / dropped on one side; a required field the other
side does not send; an enum value the consumer does not handle. Does **not** catch:
semantic drift where both shapes still validate (e.g. a unit change seconds→ms). That
residue is what the smokes (`tool-execution-loop-smoke.sh`, `approval-loop-smoke.sh`)
cover.

---

## Fix records

- **memory note "attachment no OTel" was stale** — attachment had the deps + yml since
  its own build; the gap was compose env only. Corrected here and in
  [[opsmind-architecture-gaps]].
- **tool-gateway `otel_setup.py` existed but unreached by real config** — `main.py`
  called it, but `Settings.otel_exporter` had no compose override so it always ran
  `console`. Now `OTEL_EXPORTER=otlp` + endpoint in compose.
- **SPEC-XREL-001 bug caught by writing Part C** — the eval-improvement
  `RabbitMqEventPublisherAdapter` (added in SPEC-XREL-001) re-wrapped `record.payload`
  in a fresh envelope. But eval-improvement's `application.outbox_codec.build_outbox_record`
  ALREADY assembles the full 06-event-contracts envelope into `OutboxRecord.payload`
  (unlike agent-runtime, whose `OutboxRecord.payload` is only the inner business
  payload). The adapter was double-nesting: `{...envelope..., payload: {...envelope...,
  payload: {...actual...}}}`, which would have broken agent-runtime's
  `ImprovementPromotedEventRequest` payload-flattening at runtime. Fixed: the adapter
  now publishes `record.payload` verbatim. The `improvement-promoted-v1.json` fixture
  is generated from the real `build_outbox_record`, and both the eval producer test and
  the event-relay consumer test pin the (single) envelope shape — this is exactly the
  hand-mirrored-contract drift Part C exists to catch, found while building it.
- No behaviour change to any existing green test: Part A is gated `matchIfMissing=true`
  on `noop`; Part B's instrumentor is additive + guarded; Part C is all new test files.
