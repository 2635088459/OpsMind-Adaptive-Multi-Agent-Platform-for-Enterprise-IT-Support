# SPEC-XREL-001 — Python event relay + WAITING_FOR_APPROVAL recovery

Status: implemented 2026-09-09
Owner: platform / cross-cutting (no single domain)
Related: [[opsmind-architecture-gaps]] P1, `RecoverStaleToolWaitsService` (SPEC-ARO, tool-wait sibling)

---

## 1. Problem

The platform's Java services (`ticket-workflow`, `policy-approval-governance`,
`user-access-authentication`) each run a real `@RabbitListener` bound to a durable
queue on the shared `opsmind.events` topic exchange. The Python services
(`agent-runtime`, `tool-integration-gateway`, `memory-knowledge`,
`evaluation-improvement`) do **not** — each only exposes an HTTP "event listener"
endpoint that "a future RabbitMQ consumer will call into" (every
`adapters/events/rabbitmq_consumer.py` / `infrastructure/messaging/rabbitmq_consumer.py`
in the repo is an empty placeholder module that says exactly this).

Consequences on the live platform:

1. **HIGH-risk action / tool approvals never resume automatically.** When the agent
   proposes a high-risk self-service action, `agent-runtime` parks the workflow in
   `WAITING_FOR_APPROVAL` and `tool-integration-gateway` parks the tool request in
   `WAITING_APPROVAL`. A human decides in `policy-approval-governance`, which publishes
   `approval.granted.v1` / `approval.denied.v1` to `opsmind.events`. Nothing consumes
   those into the two Python services, so the workflow / tool request sits parked
   forever. (The low-risk tool path is unaffected — that became synchronous in the P0-1
   fix.)

2. **`improvement.promoted.v1` is inert.** `evaluation-improvement` promotes a candidate
   and appends `improvement.promoted.v1` to its outbox, but its `EventPublisherPort` is
   still `LoggingEventPublisherAdapter` — the event is logged, never published — and even
   if it were published, nothing routes it to `agent-runtime`'s
   `/events/improvement-promoted` endpoint that adopts the promoted component version.

3. **A `WAITING_FOR_APPROVAL` workflow has no timeout.** `RecoverStaleToolWaitsService`
   bounds a stuck `WAITING_TOOL`; there is no equivalent for `WAITING_FOR_APPROVAL`. If
   governance never emits a decision (approval request abandoned, expiry sweep never
   runs, the relay is down for the whole TTL window), the conversation is stuck and
   `SendMessageService`'s `state is RUNNING` precondition means every further message
   `409`s.

## 2. Scope

In scope (this spec):

- A **new `event-relay` sidecar** (`services/event-relay/`, Python + `pika` blocking
  client) that subscribes to `opsmind.events` for four routing keys, transforms each
  envelope to the target Python service's HTTP event-endpoint contract, and POSTs it —
  one place, no changes to the four "sealed" Python services' application code.
  - `approval.granted.v1`  → `agent-runtime` `POST /internal/agent-runtime/v1/events`
    (+ `tool-integration-gateway` `POST /internal/tool-gateway/v1/events/approval-granted`)
  - `approval.denied.v1`   → same two endpoints (`/approval-denied` for the gateway)
  - `approval.expired.v1`  → `agent-runtime` `POST /internal/agent-runtime/v1/events`
    (decision `EXPIRED`; the gateway has no expired endpoint — its own recovery scan
    covers that side)
  - `improvement.promoted.v1` → `agent-runtime`
    `POST /internal/agent-runtime/v1/events/improvement-promoted`
- A **real RabbitMQ `EventPublisherPort` adapter for `evaluation-improvement`**
  (`RabbitMqEventPublisherAdapter`, mirroring `agent-runtime`'s), gated by
  `EVENT_PUBLISHER_ADAPTER=rabbitmq` (the value the compose file already sets and the
  service currently ignores), so `improvement.promoted.v1` actually reaches the bus.
- A **`RecoverStaleApprovalWaitsService` in `agent-runtime`**, symmetric with
  `RecoverStaleToolWaitsService`: past `approval_wait_timeout_seconds` a
  `WAITING_FOR_APPROVAL` workflow with no decision is failed (via the existing
  `FailWorkflowService`, idempotency key `approval-wait-timeout:{id}`), so the
  conversation is usable again. Exposed at
  `POST /internal/agent-runtime/v1/admin/workflows/approval-wait-recovery-scan` and
  wired into `dispatch-scheduler.sh`.

**Added in a follow-up (2026-09-09, same relay)** — `memory-knowledge`
learning-from-resolved-tickets consumption: the relay now also bridges
`ticket.resolved.v1` / `ticket.closed.v1` (from ticket-workflow) and
`workflow.completed.v1` / `workflow.failed.v1` (from agent-runtime) to
`POST /internal/memory/v1/events/{ticket-resolved,ticket-closed,workflow-completed,workflow-failed}`.
memory-knowledge's request schemas already carry `@model_validator`s that unwrap the
raw outbox envelope (both upstream shapes), so the relay forwards the consumed body
verbatim — same pattern as `improvement.promoted.v1`. This closes "the knowledge base
does not grow from real resolutions": memory-knowledge's candidate-memory pipeline
(SPEC-MK-010+) was fully built and only lacked an event source. One implementation
detail this surfaced: ticket-workflow serializes the **unversioned** `eventType`
(`ticket.resolved`) with no `routingKey` in the body, so `parse_envelope` now takes the
AMQP routing key from the pika delivery as the authoritative event type (`routing_key`
> `routingKey` > `eventType`).

Explicitly **out** of scope (deferred, noted in [[opsmind-architecture-gaps]]):

- Replacing the Java services' real consumers with the relay. They keep their own
  queues; the relay only fans out to the Python HTTP seams.
- Turning the relay into the general outbox transport for the Python publishers. Each
  Python publisher still publishes its own events directly to `opsmind.events`; the
  relay only *consumes*.

## 3. Wire contracts (as verified in code)

### 3.1 What governance publishes

`OutboxDispatchService.buildPayload` wraps every event in the shared envelope and
`RabbitGovernanceEventPublisher` sends it to exchange `opsmind.events` with
**routing key = `eventType`** (`approval.granted.v1` etc.), `message_id = outboxId`,
`content_type = application/json`.

```jsonc
{
  "eventId": "<uuid>",
  "eventType": "approval.granted.v1",
  "producer": "policy-approval-governance-service",
  "schemaVersion": 1,
  "aggregateId": "<approvalRequestId>",
  "ticketId": "<uuid|absent>",
  "correlationId": "<string>",          // NOT guaranteed to be a UUID
  "causationId": "<string|absent>",
  "occurredAt": "2026-09-09T12:34:56Z",
  "payload": {
    "approvalRequestId": "<uuid>",
    "requestKey": "...",
    "sourceDomain": "agent-runtime|tool-integration-gateway|ticket-workflow",
    "sourceRequestId": "...",
    "requestHash": "...",
    "workflowInstanceId": "<uuid|null>",
    "toolRequestId": "<uuid|null>",
    "policyDecisionId": "<uuid|null>",
    "decidedBy": "<subject>",
    "reason": "<string|null>",
    "conditions": [{"type": "...", "detail": "..."}],   // granted only
    "separationOfDutiesCheck": { ... }                  // granted only
    // denied: no conditions/sod; expired: no decidedBy/reason, has "expiresAt"
  }
}
```

### 3.2 `agent-runtime` `POST /internal/agent-runtime/v1/events`

`RuntimeEventRequest` — **strict**, no envelope-normalising validator (unlike the
`ticket-*` routes):

| field | type | relay source |
|---|---|---|
| `event_id` | non-empty str | `env.eventId` |
| `event_type` | non-empty str | **always `approval.granted.v1`** — `ConsumeRuntimeEventService` routes only that constant; the `decision` field in the payload discriminates granted / denied / expired (matches `consume_approval.py`'s documented single-event-type design) |
| `producer` | non-empty str | `env.producer` |
| `schema_version` | int ≥ 1 | `max(env.schemaVersion, 1)` |
| `correlation_id` | **UUID** | `env.correlationId` coerced: parse as UUID, else `uuid5(URL, "opsmind:correlation:<raw>")` |
| `causation_id` | **UUID** | `env.causationId or env.eventId`, same coercion |
| `ticket_id` | **UUID** | `env.ticketId`, same coercion (value only used for audit) |
| `workflow_instance_id` | **UUID** | `payload.workflowInstanceId` — if absent / not a UUID, **the agent-runtime delivery is skipped** (not DLQ'd) |
| `expected_workflow_version` | int? | omitted (staleness check opt-in) |
| `occurred_at` | datetime | `env.occurredAt` |
| `payload` | non-empty str | `json.dumps({"approvalRequestId": ..., "decision": "APPROVED"\|"DENIED"\|"EXPIRED", "approvedBy": <decidedBy>})` |

`ConsumeApprovalService.apply` needs exactly `approvalRequestId`, `decision`,
`approvedBy` in that inner payload. `decision == "APPROVED"` wakes
`WAITING_FOR_APPROVAL → RUNNING` + writes a `RECOVERY_SNAPSHOT` checkpoint; any other
value fails the workflow via `FailWorkflowService` (idempotency
`approval-reject:{workflowInstanceId}`). A workflow not (or no longer) in
`WAITING_FOR_APPROVAL` is a **200 no-op** (documented duplicate/stale handling).

Response codes → relay outcome: `2xx` / `404` (`WORKFLOW_INSTANCE_NOT_FOUND`) / `409`
(`STALE_RUNTIME_EVENT`, invalid transition) / `422` (`POISON_EVENT`) ⇒ **ack**
(delivered or permanently un-actionable). `408` / `429` / `5xx` / transport error ⇒
**retry**. `400` / other 4xx ⇒ **dead-letter** (relay transform bug — must be seen).

### 3.3 `agent-runtime` `POST /internal/agent-runtime/v1/events/improvement-promoted`

`ImprovementPromotedEventRequest` already carries a `@model_validator(mode="before")`
that unwraps `evaluation-improvement`'s outbox envelope
(`{eventId, eventType, occurredAt, producer, ...payload}`). The relay therefore POSTs
the **raw consumed envelope body unchanged**. The inner `payload` must contain
snake_case `candidate_id`, `candidate_type`, `target_component`, `promoted_version`,
`proposed_change` — which is what
`CreateImprovementCandidateService` appends (see §3.5).

### 3.4 `tool-integration-gateway` `POST /internal/tool-gateway/v1/events/approval-granted` | `/approval-denied`

`ApprovalGrantedEventRequest` / `ApprovalDeniedEventRequest` — flat fields:

| field | granted | denied | relay source |
|---|---|---|---|
| `event_id` | ✓ | ✓ | `env.eventId` |
| `approval_request_id` | ✓ | ✓ | `payload.approvalRequestId` |
| `tool_request_id` | ✓ | ✓ | `payload.toolRequestId` — **if absent, the gateway delivery is skipped** |
| `ticket_id` | opt | opt | `env.ticketId` |
| `workflow_instance_id` | opt | opt | `payload.workflowInstanceId` |
| `approved_by` | ✓ | — | `payload.decidedBy` |
| `denied_by` | — | ✓ | `payload.decidedBy` |
| `denial_reason` | — | opt | `payload.reason` |
| `constraints` | opt dict | — | `{}` (governance sends a *list* of `{type,detail}`; the gateway wants a dict and only reads named keys — passing `{}` is safe and honest) |
| `correlation_id` | ✓ | ✓ | `env.correlationId` (raw string; the gateway does not parse it as a UUID) |

`consume_approval_decision` dedups by `event_id`, no-ops unless the tool request is
`WAITING_APPROVAL`, and audits + `403`s on an approval-linkage mismatch.

Response codes → relay outcome: `2xx` / `403` (`APPROVAL_LINKAGE_MISMATCH`) / `404`
(`TOOL_REQUEST_NOT_FOUND`) / `409` (`INVALID_STATE_TRANSITION`) ⇒ **ack**. `5xx` /
`408` / `429` / transport ⇒ **retry**. `400` / other ⇒ **dead-letter**.

### 3.5 What `evaluation-improvement` publishes for `improvement.promoted.v1`

`CreateImprovementCandidateService.promote(...)` appends an outbox row for
`improvement.promoted.v1`, aggregate id = candidate id, payload =
`{candidate_id, candidate_type, target_component, promoted_version, proposed_change}`.
The new `RabbitMqEventPublisherAdapter` assembles the same envelope shape
`agent-runtime`'s adapter does (`eventId`/`eventType`/`aggregateId`/`occurredAt`/
`correlationId`/`payload`) and publishes to `opsmind.events` with routing key =
`event_type`.

## 4. The relay in detail

### 4.1 Topology

- Exchange: `opsmind.events` (topic, durable) — declared idempotently, already owned by
  the Java services.
- Queue: `event-relay.python-fanout.v1` (durable), args
  `x-dead-letter-exchange = opsmind.dlx`,
  `x-dead-letter-routing-key = event-relay.python-fanout.dlq.v1`.
- DLQ: `event-relay.python-fanout.dlq.v1` (durable) bound to `opsmind.dlx` (topic,
  durable) with that routing key.
- Bindings on the main queue: `approval.granted.v1`, `approval.denied.v1`,
  `approval.expired.v1`, `improvement.promoted.v1`.
- `basic_qos(prefetch_count = RELAY_PREFETCH)` (default 8).

### 4.2 Per-message algorithm

1. Touch the heartbeat file (also touched every ~5 s by the idle
   `process_data_events` loop — the container healthcheck reads its mtime).
2. `parse_envelope(body)`. On any parse error → `basic_nack(requeue=False)` → DLQ,
   log `action=relay_poison_envelope`.
3. `plan_deliveries(env)` → 0, 1, or 2 `Delivery` objects (target, path, json body).
   0 (no usable linkage ids, or unknown event type) → `basic_ack`, log
   `action=relay_no_delivery`.
4. For each delivery: POST with up to `RELAY_MAX_ATTEMPTS` (default 3) tries,
   `RELAY_BACKOFF_SECONDS * attempt` between them, classify the response
   (`ack` / `retry` / `dlq`).
5. Combine: **any `retry`** → sleep `RELAY_REQUEUE_DELAY_SECONDS` (default 15) then
   `basic_nack(requeue=True)` — the whole message is redelivered; every downstream
   endpoint dedups by `event_id`, so an already-delivered target simply no-ops on the
   replay. **else any `dlq`** → `basic_nack(requeue=False)` → DLQ. **else** →
   `basic_ack`.

At-least-once, idempotent downstream. The relay never itself dedups — the four target
endpoints already do (`processed_events` / `(event_id, consumer_name)`).

### 4.3 Config (env)

| var | default | meaning |
|---|---|---|
| `RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` / `_VHOST` | `localhost` / `5672` / `guest` / `guest` / `/` | broker |
| `RABBITMQ_EXCHANGE` | `opsmind.events` | source exchange |
| `RABBITMQ_DLX` | `opsmind.dlx` | dead-letter exchange |
| `RELAY_QUEUE` | `event-relay.python-fanout.v1` | main queue |
| `RELAY_DLQ_ROUTING_KEY` | `event-relay.python-fanout.dlq.v1` | dlq binding key |
| `AGENT_RUNTIME_BASE_URL` | `http://agent-runtime-service:8000` | fan-out target |
| `TOOL_GATEWAY_BASE_URL` | `http://tool-integration-gateway:8020` | fan-out target |
| `RELAY_HTTP_TIMEOUT_SECONDS` | `10` | per POST |
| `RELAY_MAX_ATTEMPTS` | `3` | per delivery |
| `RELAY_BACKOFF_SECONDS` | `2` | linear backoff unit |
| `RELAY_REQUEUE_DELAY_SECONDS` | `15` | pause before nack-requeue |
| `RELAY_PREFETCH` | `8` | unacked window |
| `RELAY_HEARTBEAT_FILE` | `/tmp/relay-alive` | healthcheck liveness marker |
| `LOG_LEVEL` | `INFO` | root logger |

### 4.4 Logging

One structured line per stage, all prefixed `action=relay_*`:

```
action=relay_consumed        event_id=.. event_type=.. producer=.. deliveries=2
action=relay_delivery        event_id=.. target=agent-runtime path=/internal/.. attempt=1 status=200 outcome=ack
action=relay_delivery_error  event_id=.. target=tool-gateway path=/internal/.. attempt=2 error=ConnectError
action=relay_settled         event_id=.. outcome=ack|retry|dlq deliveries=2 acked=1 retried=0 dlq=0
action=relay_no_delivery     event_id=.. event_type=.. reason="no workflowInstanceId/toolRequestId in payload"
action=relay_poison_envelope raw_len=.. error=..
action=relay_connected       exchange=opsmind.events queue=event-relay.python-fanout.v1 bindings=[..]
```

## 5. `RecoverStaleApprovalWaitsService`

Mirrors `RecoverStaleToolWaitsService` but simpler (no Agent Task, no Tool Request —
`WAITING_FOR_APPROVAL` is a Workflow-Instance-only wait):

- `scan_and_recover(batch_size)`:
  - `now = clock.now()`
  - `stale = [w for w in workflow_instance_repository.find_non_terminal(batch_size)
     if w.state is WorkflowState.WAITING_FOR_APPROVAL and w.updated_at < now - timeout]`
    (reuses the existing `find_non_terminal` "oldest `updated_at` first" query —
    the same low-frequency batch-scan profile as the other recovery scans; no new
    repository method / index)
  - for each: `FailWorkflowService.fail(FailWorkflowCommand(w.id,
    IdempotencyKey("approval-wait-timeout:{w.id}"),
    "approval wait timed out after {N}s with no approval decision"))`, swallowing
    `InvalidWorkflowStateException` / version conflict as a lost race.
  - returns `ApprovalWaitRecoveryReport(scanned, timed_out, scanned_at)`.
- Deliberately checkpoint-free / outbox-free, exactly like `RecoverStaleToolWaitsService`
  and `RecoverExpiredLeaseTasksService`. `FailWorkflowService` already publishes its own
  `workflow.failed` outbox event and is idempotent.
- Structured log: `action=approval_wait_recovery_timeout workflow_instance_id=.. ticket_id=.. timeout_seconds=..`.
- Setting `approval_wait_timeout_seconds: float = 1800.0` (30 min — an approval is a
  human decision, so the bound is far longer than the 300 s tool-wait bound).
- Admin route `POST /internal/agent-runtime/v1/admin/workflows/approval-wait-recovery-scan`
  (`X-Actor-Id` audit log before/after, mirroring the tool-wait route), added to
  `dispatch-scheduler.sh`'s recovery block.

### 5.1 Relay vs. recovery — how they interact

The relay is the **fast path** for `approval.expired.v1` (governance's own expiry sweep
emits it; the relay turns it into a `decision=EXPIRED` → `FailWorkflowService` via
`approval-reject:{id}`). `RecoverStaleApprovalWaitsService` is the **safety net** for
when no decision event ever arrives (approval abandoned, relay down for the whole
window, governance expiry sweep disabled). Both converge on the same terminal outcome
through the same `FailWorkflowService`; the two idempotency keys
(`approval-reject:{id}` from the event path, `approval-wait-timeout:{id}` from the
scan) are distinct but the second `fail()` on an already-terminal workflow is a
caught no-op, so a race between them is harmless.

## 5b. Observability (added 2026-09-10)

The relay was log-only and its DLQ had no watcher. Now:

- **OpenTelemetry SDK** wired in `observability.configure_observability` (mirrors every
  other Python service — `otel_exporter` `console` default / `otlp`,
  `otel_exporter_otlp_endpoint`, `otel_service_name`). `full-platform.yml` sets
  `OTEL_EXPORTER=otlp` + `:14317` + `service.namespace=shared`; the Helm `event-relay`
  entry sets `OTEL_RESOURCE_ATTRIBUTES=service.namespace=shared` (shared config already
  carries the endpoint).
- **Spans.** `event_relay.consume` (CONSUMER) per message — it `extract()`s a W3C
  `traceparent` off the AMQP `properties.headers` (no producer stamps one *today*, so
  this usually roots a fresh trace, but it is ready for when one does), attributes
  `event.{id,type,producer}` + `relay.outcome`. `event_relay.deliver <target>` (CLIENT)
  per HTTP POST, with `inject()` putting `traceparent` onto the request headers so the
  downstream FastAPI service (instrumented in SPEC-XOBS-001) continues the same trace —
  a delivery failure now shows in Tempo as one `event-relay → target` trace.
- **Metrics** (vendor-neutral API in `metrics.py`; reach Prometheus via the
  otel-collector's prometheus exporter): `event_relay_messages_total{event_type,
  outcome}` (`outcome` ∈ ack|retry|dlq|no_delivery|poison), `event_relay_deliveries_total
  {target, outcome}`, `event_relay_dlq_total{reason}` (`poison_envelope` |
  `delivery_rejected`), `event_relay_poll_total` (pump-loop tick, traffic-independent
  liveness).
- **Alerts** (`infrastructure/observability/rules/{recording,alerting}/event-relay.yml`,
  runbook `runbooks/EventRelay.md`, in the `observability-platform-ci.yml` promtool
  list): `EventRelayDeadLettering` (`event_relay:dlq:rate5m > 0` for 10m, critical),
  `EventRelayStalled` (`rate(event_relay_poll_total[5m]) == 0` for 5m, critical — the
  process is down or wedged) and `EventRelayBrokerUnreachable`
  (`event_relay:broker_errors:rate5m > 0` for 5m, warning — the process is up and
  retrying but cannot reach RabbitMQ).
- **Liveness during a broker outage.** The container healthcheck / k8s exec probe reads
  the mtime of `RELAY_HEARTBEAT_FILE`. Originally only `_connect()`/`_pump()` touched it,
  so a relay that was *correctly* sitting in its reconnect loop (RabbitMQ down) went
  stale and would be CrashLoop-killed. `run_forever`'s reconnect branch now also touches
  the heartbeat and records `event_relay_broker_errors_total` + a `poll` tick on every
  retry. `EventRelayStalled` still distinguishes "process gone" (no poll ticks at all)
  from `EventRelayBrokerUnreachable` (poll ticks continue, broker-error counter climbs).
  Verified on a real kind cluster with no broker present: the relay pod held
  `1/1 Running`, `Restart Count: 0` for 3+ minutes.
  (`consumer.py`'s `_touch_heartbeat` is a `RelayConsumer` method — an earlier refactor
  briefly left it dangling after a module-level function; the exec probe's
  `AttributeError` CrashLoop caught it.)

## 6. Test plan

- **relay unit** (`services/event-relay/tests/`, no broker):
  - `test_envelope.py` — parse a real governance granted / denied / expired envelope and
    an eval-improvement promoted envelope; string vs dict `payload`; UUID coercion of a
    non-UUID `correlationId`; missing `causationId` falls back to `eventId`.
  - `test_routing.py` — granted with both `workflowInstanceId` + `toolRequestId` → 2
    deliveries with the exact bodies from §3.2/§3.4; granted with only
    `workflowInstanceId` → 1 (agent-runtime); denied → `/approval-denied` + `denied_by`
    + `denial_reason`; expired → 1 agent-runtime delivery, decision `EXPIRED`, no gateway
    delivery even if `toolRequestId` present; `improvement.promoted.v1` → raw body to
    `/events/improvement-promoted`; unknown routing key / no linkage ids → `[]`.
  - `test_delivery.py` — `classify()` truth table; `httpx.MockTransport` driving
    `deliver()` through ack / retry-then-ack / retry-exhausted / dlq / transport-error.
- **eval-improvement** (`tests/infrastructure/`): `RabbitMqEventPublisherAdapter`
  envelope shape + publish-failure returns `False` (never raises), mirroring
  `agent-runtime`'s adapter tests; container wiring picks it up when
  `event_publisher_adapter == "rabbitmq"`.
- **agent-runtime** (`tests/application/test_recover_stale_approval_waits_service.py`):
  a `WAITING_FOR_APPROVAL` workflow older than the timeout is failed; one newer than the
  timeout is untouched; a non-`WAITING_FOR_APPROVAL` non-terminal workflow is untouched;
  a already-terminal race is a no-op; report counts. Plus a `test_admin_app.py` case for
  the new route.
- **smoke** (`scripts/approval-loop-smoke.sh`, added to `frontend-e2e.yml`): drive a
  HIGH-risk action end to end — conversation → propose → confirm → workflow parks in
  `WAITING_FOR_APPROVAL` + governance approval request created → approve it in
  governance → dispatch-scheduler drains governance's outbox → relay consumes
  `approval.granted.v1` → agent-runtime workflow leaves `WAITING_FOR_APPROVAL` → tool
  executes → `COMPLETED`.

## 7. Files

New:

```
docs/specs/cross-cutting/SPEC-XREL-001-python-event-relay-and-approval-wait-recovery.md   (this doc)
services/event-relay/pyproject.toml
services/event-relay/Dockerfile
services/event-relay/README.md
services/event-relay/src/event_relay/{__init__,__main__,settings,envelope,routing,delivery,consumer}.py
services/event-relay/tests/{__init__,conftest,test_envelope,test_routing,test_delivery}.py
services/evaluation-improvement-service/src/evaluationimprovement/infrastructure/messaging/rabbitmq_publisher.py   (was empty placeholder)
services/evaluation-improvement-service/tests/infrastructure/test_rabbitmq_event_publisher.py
services/agent-runtime-service/src/agentruntime/application/services/recover_stale_approval_waits.py
services/agent-runtime-service/tests/application/test_recover_stale_approval_waits_service.py
scripts/approval-loop-smoke.sh
```

New (§5b Observability, 2026-09-10):

```
services/event-relay/src/event_relay/observability.py         (configure_observability — OTel SDK wiring)
services/event-relay/src/event_relay/metrics.py               (event_relay_{messages,deliveries,dlq,poll,broker_errors}_total)
services/event-relay/tests/test_observability.py              (traceparent inject + span outcome/status, 4 tests)
infrastructure/observability/rules/recording/event-relay.yml  (6 recording rules)
infrastructure/observability/rules/alerting/event-relay.yml   (EventRelayDeadLettering / EventRelayStalled / EventRelayBrokerUnreachable)
infrastructure/observability/runbooks/EventRelay.md
```

Modified (§5b):

```
services/event-relay/pyproject.toml + uv.lock          (+ opentelemetry-{api,sdk,exporter-otlp-proto-grpc})
services/event-relay/src/event_relay/settings.py       (+ otel_exporter, otel_exporter_otlp_endpoint, otel_service_name)
services/event-relay/src/event_relay/__main__.py       (configure_observability before RelayConsumer)
services/event-relay/src/event_relay/consumer.py       (extract() traceparent; event_relay.consume span; metrics; heartbeat during broker retry)
services/event-relay/src/event_relay/delivery.py       (event_relay.deliver span; inject() traceparent into the HTTP POST; delivery metrics)
services/event-relay/Dockerfile                        (+ the 3 opentelemetry packages; numeric USER 65532)
infrastructure/docker-compose/full-platform.yml        (event-relay: OTEL_EXPORTER=otlp + :14317 + service.namespace=shared)
infrastructure/helm/opsmind/values.yaml                (event-relay OTEL_RESOURCE_ATTRIBUTES; podSecurityContext runAsUser/Group/fsGroup 65532)
infrastructure/helm/opsmind/README.md                  (+ "Verified against a real cluster" — the runAsNonRoot + heartbeat findings)
.github/workflows/observability-platform-ci.yml        (+ event-relay rule files in the promtool list)
services/{agent-runtime,attachment,evaluation-improvement,memory-knowledge,policy-approval-governance,ticket-workflow,tool-integration-gateway,user-access-authentication}-service/Dockerfile   (numeric USER 65532 for k8s runAsNonRoot)
```

Modified:

```
infrastructure/docker-compose/full-platform.yml            (+ event-relay service; eval-improvement depends_on unchanged)
infrastructure/docker-compose/dispatch-scheduler.sh        (+ approval-wait-recovery-scan in the recovery block)
services/evaluation-improvement-service/src/evaluationimprovement/settings.py       (+ event_publisher_adapter, rabbitmq_*)
services/evaluation-improvement-service/src/evaluationimprovement/container.py       (wire RabbitMqEventPublisherAdapter)
services/agent-runtime-service/src/agentruntime/settings.py                          (+ approval_wait_timeout_seconds)
services/agent-runtime-service/src/agentruntime/application/ports_in.py              (+ ApprovalWaitRecoveryPort)
services/agent-runtime-service/src/agentruntime/application/views.py                 (+ ApprovalWaitRecoveryReport)
services/agent-runtime-service/src/agentruntime/container.py                         (wire RecoverStaleApprovalWaitsService)
services/agent-runtime-service/src/agentruntime/interfaces/admin/{router,schemas,mapper}.py   (+ the scan route)
.github/workflows/frontend-e2e.yml                         (+ approval-loop-smoke step)
```
