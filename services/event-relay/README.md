# event-relay

SPEC-XREL-001. A sidecar that bridges four cross-domain events on the shared
`opsmind.events` topic exchange into the Python services' HTTP "event listener"
endpoints, because those services (`agent-runtime`, `tool-integration-gateway`)
ship an HTTP seam but no real RabbitMQ consumer.

| consumed routing key | fans out to |
|---|---|
| `approval.granted.v1` | `agent-runtime` `POST /internal/agent-runtime/v1/events` (if `payload.workflowInstanceId`) + `tool-integration-gateway` `POST /internal/tool-gateway/v1/events/approval-granted` (if `payload.toolRequestId`) |
| `approval.denied.v1` | same two, `/approval-denied` for the gateway |
| `approval.expired.v1` | `agent-runtime` `POST /internal/agent-runtime/v1/events` with `decision=EXPIRED` (gateway has no expired endpoint) |
| `improvement.promoted.v1` | `agent-runtime` `POST /internal/agent-runtime/v1/events/improvement-promoted` (raw envelope forwarded) |
| `ticket.resolved.v1` / `ticket.closed.v1` | `memory-knowledge` `POST /internal/memory/v1/events/ticket-{resolved,closed}` (raw envelope forwarded — feeds the candidate-memory pipeline so the knowledge base grows from real resolutions) |
| `workflow.completed.v1` / `workflow.failed.v1` | `memory-knowledge` `POST /internal/memory/v1/events/workflow-{completed,failed}` (raw envelope forwarded) |

At-least-once. Every target endpoint already dedups by `event_id`, so the relay
never dedups itself.

## Settlement

Per message, across its 1–2 deliveries:

- **ack** — 2xx, or 403/404/409/410/422 (each target's documented "understood
  rejection": already dedup'd, already resumed, unknown id).
- **retry** — 408/429/5xx or transport error → `nack(requeue=True)` after a
  `RELAY_REQUEUE_DELAY_SECONDS` pause (each delivery already retried
  `RELAY_MAX_ATTEMPTS` times in-process first).
- **dlq** — 400 / other permanent 4xx → `nack(requeue=False)` → `opsmind.dlx` →
  `event-relay.python-fanout.dlq.v1`. Means the relay built a body the target
  rejects — a transform bug to look at.

## Run

```
python -m event_relay
```

Config is all env — see `src/event_relay/settings.py`. Defaults are the
compose-network hostnames.

## Test

```
uv run --with pytest --with httpx --with pika --with pydantic-settings pytest
# or, with the project installed:
pytest
```

Tests are broker-free: pure transform (`test_routing.py`, `test_envelope.py`) and
`httpx.MockTransport`-driven delivery (`test_delivery.py`).
