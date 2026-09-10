"""SPEC-XREL-001 §Observability: the relay's own OpenTelemetry counters. Only the
vendor-neutral `opentelemetry.metrics` API is touched here — the SDK is wired once in
`observability.configure_observability`, exactly the split every other service uses
between `application.telemetry` and its `infrastructure.observability`.

Low-cardinality labels only:
  event_relay_messages_total{event_type, outcome}   outcome ∈ ack|retry|dlq|no_delivery|poison
  event_relay_deliveries_total{target, outcome}     one per HTTP POST; outcome ∈ ack|retry|dlq
  event_relay_dlq_total{reason}                     reason ∈ poison_envelope|delivery_rejected
"""

from __future__ import annotations

from opentelemetry import metrics

_meter = metrics.get_meter("event_relay")

_messages = _meter.create_counter(
    "event_relay_messages_total",
    description="opsmind.events messages the relay has settled, by event type and final outcome",
)
_deliveries = _meter.create_counter(
    "event_relay_deliveries_total",
    description="HTTP fan-out attempts to a downstream service, by target and outcome",
)
_dlq = _meter.create_counter(
    "event_relay_dlq_total",
    description="messages the relay dead-lettered to opsmind.dlx, by reason",
)
_poll = _meter.create_counter(
    "event_relay_poll_total",
    description="broker poll iterations — a traffic-independent liveness signal (the pump loop ticks every ~5s)",
)
_broker_errors = _meter.create_counter(
    "event_relay_broker_errors_total",
    description="AMQP connection failures — the relay is up but cannot reach RabbitMQ and is retrying",
)


def record_poll() -> None:
    _poll.add(1)


def record_broker_error() -> None:
    _broker_errors.add(1)


def record_message(event_type: str, outcome: str) -> None:
    _messages.add(1, {"event_type": event_type, "outcome": outcome})


def record_delivery(target: str, outcome: str) -> None:
    _deliveries.add(1, {"target": target, "outcome": outcome})


def record_dlq(reason: str) -> None:
    _dlq.add(1, {"reason": reason})
