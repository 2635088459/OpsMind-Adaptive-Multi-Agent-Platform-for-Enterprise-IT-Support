# EventRelay

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-XREL-001
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/specs/cross-cutting/SPEC-XREL-001-python-event-relay-and-approval-wait-recovery.md

The `event-relay` sidecar (`opsmind-event-relay`) is a `pika` consumer with no HTTP
surface. It subscribes to `opsmind.events` for the cross-domain events the Python
services (`agent-runtime`, `tool-integration-gateway`, `memory-knowledge`) have no
RabbitMQ consumer of their own for, transforms each envelope, and POSTs it to that
service's `/internal/.../events` seam. Its own telemetry
(`event_relay_{messages,deliveries,dlq,poll}_total`) reaches Prometheus through the
otel-collector (`job=otel-collector`).

Covers two alerts:

## `EventRelayDeadLettering`

**Impact.** Approval decisions (`approval.granted/denied/expired.v1`), promoted
improvements (`improvement.promoted.v1`), and memory-source events
(`ticket.resolved/closed.v1`, `workflow.completed/failed.v1`) are being dropped to
`opsmind.dlx` → `event-relay.python-fanout.dlq.v1` instead of reaching their target.
Concretely: a `WAITING_FOR_APPROVAL` workflow will not resume from an approval, a
promoted prompt will not go live, and the knowledge base stops learning from resolved
tickets — until the DLQ is drained and replayed.

**Detection.** `event_relay:dlq:rate5m > 0` for 10m
(`sum(rate(event_relay_dlq_total[5m]))`). The `reason` label distinguishes
`poison_envelope` (unparseable message) from `delivery_rejected` (a downstream 400 /
other permanent 4xx — i.e. the relay built a body the target refuses, a transform bug).

**Triage.**
1. `docker logs opsmind-event-relay | grep -E 'relay_poison_envelope|relay_settled outcome=dlq'`
   — get the `event_id` / `event_type` / downstream status.
2. `poison_envelope`: a producer changed the envelope shape. Diff against the
   contracts fixtures in `contracts/` and the schema the relay parses
   (`event_relay/envelope.py`).
3. `delivery_rejected`: the transform in `event_relay/routing.py` produced a body the
   target schema rejects — check the target's request model and the
   `contracts/*.json` fixture for that seam.
4. Inspect the DLQ:
   `docker exec opsmind-rabbitmq rabbitmqadmin get queue=event-relay.python-fanout.dlq.v1 count=10 ackmode=reject_requeue_true`
5. After the fix ships, replay: shovel/re-publish the DLQ messages back onto
   `opsmind.events` with their original routing key (every target dedups by
   `event_id`, so replay is safe).

## `EventRelayStalled`

**Impact.** The relay's pump loop is not running — it is down, disconnected from
RabbitMQ, or wedged. Nothing on `opsmind.events` is being bridged. Same downstream
impact as above, for every event type, until it recovers.

**Detection.** `sum(rate(event_relay_poll_total[5m])) == 0` for 5m. The pump loop
increments this counter every ~5 s while connected, independent of message traffic —
so a flat line is a real liveness failure, not just a quiet period.

**Triage.**
1. `docker ps -a --filter name=opsmind-event-relay` — is it running / restarting /
   exited? `docker logs --tail 100 opsmind-event-relay`.
2. Look for `action=relay_broker_error` (RabbitMQ unreachable — check
   `opsmind-rabbitmq` health) or a Python traceback.
3. The container healthcheck is a heartbeat file (`RELAY_HEARTBEAT_FILE`, default
   `/tmp/relay-alive`); `docker inspect -f '{{json .State.Health}}' opsmind-event-relay`.
4. Restart: `docker compose -f infrastructure/docker-compose/local-platform.yml -f infrastructure/docker-compose/full-platform.yml up -d event-relay`.
   The relay reconnects on its own; messages published while it was down are still
   queued on `event-relay.python-fanout.v1` (durable) and get processed on reconnect.

## Trace correlation

Since SPEC-XREL-001 §Observability the relay emits a `event_relay.consume` CONSUMER
span per message and a `event_relay.deliver <target>` CLIENT span per HTTP POST, and
injects W3C `traceparent` onto the POST — so a delivery failure shows up in Tempo as
one trace spanning `event-relay` → the target service. Filter the Event Relay
dashboard's log panel by the `event.id` on the failing span.
