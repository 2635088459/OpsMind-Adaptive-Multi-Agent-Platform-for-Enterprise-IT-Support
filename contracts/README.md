# `contracts/` — cross-service wire-contract fixtures

SPEC-XOBS-001 Part C. Every cross-service message / DTO in this platform is
**hand-mirrored**: the producer builds a dict / DTO, the consumer re-declares a
matching schema in its own codebase, and nothing checks the two agree until a real
request flows at runtime. Two production bugs this pattern caused (both found only by
running an end-to-end flow):

- **`verificationCondition`** — eval-improvement types it `dict`; agent-runtime first
  declared it `str`. `{}` from eval → `400` at agent-runtime.
- **`side_effect_kind`** — tool-gateway's Postgres connector repo never persisted it and
  re-derived it from a capability-name keyword heuristic on read, silently making every
  `identity.user.*` capability `READ_ONLY` → every real execution `UNSUPPORTED_CAPABILITY`.

## The pattern (consumer-driven contract fixtures, no framework)

Each `.json` file here is **one real example** of a contract. The fixture index table
at the bottom is the authoritative note for each: its producer, its consumer(s), and
the field rules that matter (the `guards` column).

Every producer and every consumer of a contract adds a test **in its own test suite**
that loads the shared fixture and runs it through its own real (de)serializer:

- a **consumer** test asserts its schema *accepts* the fixture and that the fields it
  depends on have the type/shape it expects;
- a **producer** test asserts the object it emits *matches* the fixture (same keys,
  same value types).

When one side changes the contract, its own test forces the fixture to change in the
same commit, and the *other* side's test then fails in that side's CI — the drift
surfaces at change time, in review, instead of at runtime in a deployed stack.

No Pact broker, no code generation, no network. Each test finds this directory with
`Path(__file__).resolve().parents[N] / "contracts"`. A missing fixture fails loudly.

Tests that pin these fixtures (SPEC-XOBS-001 Part C):

| suite | file |
|---|---|
| event-relay | `services/event-relay/tests/test_contracts.py` |
| agent-runtime | `services/agent-runtime-service/tests/contracts/test_cross_service_wire_contracts.py` |
| eval-improvement | `services/evaluation-improvement-service/tests/contracts/test_cross_service_wire_contracts.py` |
| tool-integration-gateway | `services/tool-integration-gateway/tests/contracts/test_cross_service_wire_contracts.py` |
| memory-knowledge | `services/memory-knowledge-service/tests/contracts/test_cross_service_wire_contracts.py` |

## What this catches / does not catch

**Catches:** a field renamed, retyped, or dropped on one side; a required field the
other side never sends; an enum value the consumer does not handle.

**Does not catch:** semantic drift where both shapes still validate (a unit change
seconds → ms, a meaning change). That residue is covered by the end-to-end smokes
(`scripts/tool-execution-loop-smoke.sh`, `scripts/approval-loop-smoke.sh`).

## Fixture index

| fixture | producer | consumer(s) | guards |
|---|---|---|---|
| `governance-approval-granted-v1.json` | policy-approval-governance `ApprovalGrantedEvent` + `OutboxDispatchService` envelope | event-relay → agent-runtime, tool-integration-gateway | approval-decision fan-out |
| `governance-approval-denied-v1.json` | policy-approval-governance `ApprovalDeniedEvent` | event-relay → agent-runtime, tool-integration-gateway | denial reason + `denied_by` |
| `governance-approval-expired-v1.json` | policy-approval-governance `ApprovalExpiredEvent` | event-relay → agent-runtime | expiry → `decision=EXPIRED` |
| `agent-runtime-runtime-event-request.json` | event-relay | agent-runtime `POST /internal/agent-runtime/v1/events` (`RuntimeEventRequest`) | UUID-typed ids, stringified `payload`, `schema_version ≥ 1` |
| `agent-runtime-execute-evaluation-case-request.json` | eval-improvement `HttpAgentRuntimeEvaluationAdapter` | agent-runtime `ExecuteEvaluationCaseRequest` | **`verificationCondition` is an object**, `mockSystemState` is an object |
| `agent-runtime-execute-evaluation-case-response.json` | agent-runtime `ExecuteEvaluationCaseResponse` | eval-improvement `HttpAgentRuntimeEvaluationAdapter` | `promptTokens` / `completionTokens` present alongside `costTokens` |
| `improvement-promoted-v1.json` | eval-improvement `CreateImprovementCandidateService` outbox → `RabbitMqEventPublisherAdapter` envelope | event-relay → agent-runtime `ImprovementPromotedEventRequest` | snake_case `payload` keys the model-validator flattens |
| `tool-gateway-submit-tool-request.json` | agent-runtime `HttpToolGatewayPort._submit` | tool-integration-gateway `SubmitToolRequestRequest` | `requested_by_type`, `input_payload` object, optional linkage ids |
| `tool-gateway-execute-tool-request-response.json` | tool-integration-gateway sync `/execute` (`ExecuteToolRequestResponse`) | agent-runtime `HttpToolGatewayPort._execute` | `status`, `output`, `failure_reason` |
| `tool-gateway-approval-granted-event-request.json` | event-relay | tool-integration-gateway `ApprovalGrantedEventRequest` | flat fields, `constraints` object |
| `memory-knowledge-ticket-resolved-source.json` | ticket-workflow `TicketResolvedEventMapper` + `RabbitOutboxEventPublisherAdapter` | event-relay → memory-knowledge `TicketResolvedEventRequest` | **unversioned `eventType`** (`ticket.resolved`), nested camelCase `payload`, versioned type only on the AMQP routing key |
