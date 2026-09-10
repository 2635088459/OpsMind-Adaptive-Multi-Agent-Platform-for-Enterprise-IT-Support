# EventRelay

> owner: platform-observability
> version: 1.1.0
> spec: SPEC-XREL-001
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/specs/cross-cutting/SPEC-XREL-001-python-event-relay-and-approval-wait-recovery.md

Companion runbook for the **Event Relay** dashboard (`dashboards/event-relay.json`).
Covers the three alerts `SPEC-XREL-001 §Observability` adds:
`EventRelayDeadLettering` (critical), `EventRelayStalled` (critical),
`EventRelayBrokerUnreachable` (warning).

The `event-relay` sidecar (`opsmind-event-relay`) is a `pika` consumer with no HTTP
surface. It subscribes to `opsmind.events` for the cross-domain events the Python
services (`agent-runtime`, `tool-integration-gateway`, `memory-knowledge`) have no
RabbitMQ consumer of their own for, transforms each envelope, and POSTs it to that
service's `/internal/.../events` seam. Its own telemetry
(`event_relay_{messages,deliveries,dlq,poll,broker_errors}_total`) reaches Prometheus
through the otel-collector (`job=otel-collector`); the recording rules over it are in
`rules/recording/event-relay.yml`.

## Impact

- `EventRelayDeadLettering`: approval decisions (`approval.granted/denied/expired.v1`),
  promoted improvements (`improvement.promoted.v1`) and memory-source events
  (`ticket.resolved/closed.v1`, `workflow.completed/failed.v1`) are being dropped to
  `opsmind.dlx` → `event-relay.python-fanout.dlq.v1` instead of reaching their target.
  Concretely: a `WAITING_FOR_APPROVAL` workflow will not resume from an approval, a
  promoted prompt will not go live, and the knowledge base stops learning from
  resolved tickets — until the DLQ is drained and replayed.
- `EventRelayStalled`: the relay process is down or wedged — **nothing** on
  `opsmind.events` is being bridged, for every event type, until it recovers.
- `EventRelayBrokerUnreachable`: the process is alive and retrying but its AMQP
  connection keeps failing, so it is consuming nothing. Same effect as Stalled while
  it lasts, but self-heals the moment RabbitMQ comes back — no restart needed.

## Detection

- Firing expressions:
  - `event_relay:dlq:rate5m > 0` for 10m (`sum(rate(event_relay_dlq_total[5m]))`).
    The `reason` label splits `poison_envelope` (unparseable message) from
    `delivery_rejected` (a downstream 400 / other permanent 4xx — the relay built a
    body the target refuses, a transform bug).
  - `(sum(rate(event_relay_poll_total[5m])) or vector(0)) == 0` for 5m. The pump loop
    **and** the reconnect loop increment this every ~5s while the process runs,
    independent of traffic — a flat line is a real liveness failure.
  - `event_relay:broker_errors:rate5m > 0` for 5m
    (`sum(rate(event_relay_broker_errors_total[5m]))`).
- Dashboard: `dashboards/event-relay.json` — the "Liveness" row's three stat panels
  map one-to-one to the three alerts; `EventRelayStalled` vs
  `EventRelayBrokerUnreachable` is the poll-rate panel staying green (ticking) while
  the broker-error panel goes red.
- Trace correlation: the relay emits an `event_relay.consume` CONSUMER span per
  message and an `event_relay.deliver <target>` CLIENT span per HTTP POST, injects
  W3C `traceparent` onto the POST, and logs `trace_id=<32-hex>` on every
  `action=relay_consumed` / `relay_settled` / `relay_delivery` line — so a failing
  delivery is one Tempo trace spanning `event-relay` → the target service. The
  dashboard's "Traces (Tempo, service.name=event-relay)" link opens that view.

## Triage

1. Check which alert fired — the three have unrelated root causes.
2. `EventRelayDeadLettering`:
   - `docker logs opsmind-event-relay | grep -E 'relay_poison_envelope|relay_settled .*outcome=dlq'`
     — get the `event_id` / `event_type` / `trace_id` / downstream status.
   - `poison_envelope`: a producer changed the envelope shape. Diff against the
     fixtures in `contracts/` and the schema the relay parses (`event_relay/envelope.py`).
   - `delivery_rejected`: the transform in `event_relay/routing.py` produced a body a
     target schema rejects — check that target's request model and its
     `contracts/*.json` fixture.
   - Inspect the DLQ:
     `docker exec opsmind-rabbitmq rabbitmqadmin get queue=event-relay.python-fanout.dlq.v1 count=10 ackmode=reject_requeue_true`
3. `EventRelayStalled`: `docker ps -a --filter name=opsmind-event-relay` — running /
   restarting / exited? `docker logs --tail 100 opsmind-event-relay` for a Python
   traceback. Check the heartbeat: `docker inspect -f '{{json .State.Health}}' opsmind-event-relay`.
4. `EventRelayBrokerUnreachable`: the relay logs `action=relay_broker_error
   error=... reconnecting_in=5.0s` on a loop. Check `opsmind-rabbitmq` health and the
   relay's `RABBITMQ_*` settings — the relay itself needs no action, it reconnects.

## Mitigation

- `EventRelayDeadLettering`: no safe mitigation until the transform/contract bug is
  identified — do NOT blind-replay a `delivery_rejected` message, it will just
  re-dead-letter. A `poison_envelope` from a known-good producer change can be
  fixed-forward and replayed (step in Resolution).
- `EventRelayStalled`: restart the sidecar —
  `docker compose -f infrastructure/docker-compose/local-platform.yml -f infrastructure/docker-compose/full-platform.yml up -d event-relay`
  (k8s: `kubectl -n opsmind rollout restart deploy/event-relay`). Messages published
  while it was down stay queued on the durable `event-relay.python-fanout.v1` and are
  processed on reconnect — no data loss.
- `EventRelayBrokerUnreachable`: fix RabbitMQ (restart `opsmind-rabbitmq` / check the
  broker's own alerts). The relay rejoins automatically; nothing to do on its side.

## Resolution

- `EventRelayDeadLettering`: ship the envelope-parse / transform fix, then replay —
  re-publish the DLQ messages onto `opsmind.events` with their **original routing
  key** (every target dedups by `event_id`, so replay is safe). Confirm
  `event_relay:dlq:rate5m` returns to `0` and the cumulative-DLQ panel stops growing.
- `EventRelayStalled` / `EventRelayBrokerUnreachable`: confirm
  `event_relay:poll:rate5m` is non-zero and `event_relay:broker_errors:rate5m` is `0`,
  and the "Settlement rate by outcome" panel shows `ack` traffic resuming.

## Rollback

Exact revert: `git revert <sha>` on this runbook / the rule files
(`rules/recording/event-relay.yml`, `rules/alerting/event-relay.yml`) / the dashboard
(`dashboards/event-relay.json`); `promtool check rules
rules/recording/event-relay.yml rules/alerting/event-relay.yml`; recreate Prometheus
and re-run Grafana provisioning. The relay's own observability code
(`services/event-relay/src/event_relay/{observability,metrics}.py`, the spans in
`consumer.py`/`delivery.py`) reverts with its own commit — see SPEC-XREL-001 §5b.

## Escalation

- `EventRelayDeadLettering` (`critical`): page platform-observability on-call; the
  transform/contract root cause is owned by whichever domain changed the envelope
  (identify it from the `event.producer` span attribute / the `producer` field in the
  logged envelope) — domain 08 detects the drop, the producing domain fixes it (ADR-0004).
- `EventRelayStalled` (`critical`): page platform-observability on-call — the sidecar
  itself is theirs to restart / debug.
- `EventRelayBrokerUnreachable` (`warning`): opens a ticket against the RabbitMQ
  owner's on-call; cross-reference the broker's own health alerts.

## Post-incident

Link the SPEC-XREL-001 audit reference above. Residual risk: the relay is
at-least-once (every target dedups by `event_id`) but has no bounded retry budget on
`5xx` — a downstream that is 5xx for hours keeps a message re-queuing on
`event-relay.python-fanout.v1` rather than dead-lettering it, so a *long* downstream
outage shows up as `EventRelayDeadLettering` staying quiet while the settlement panel
shows sustained `retry`. Watch the "Settlement rate by outcome" panel's `retry`
series alongside the target's own availability during any downstream incident.
