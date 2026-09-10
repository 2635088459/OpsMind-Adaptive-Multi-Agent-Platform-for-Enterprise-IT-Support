"""The broker side: declare the topology, consume `opsmind.events`, hand each
message to the pure `routing`/`delivery` code, and settle it (ack / nack-requeue
/ nack-to-DLQ).

pika BlockingConnection with a manual `process_data_events` pump (rather than
`start_consuming`) so the heartbeat-liveness file is touched on a fixed ~5 s
cadence regardless of traffic — the container healthcheck reads its mtime.
"""

from __future__ import annotations

import logging
import time

import httpx
import pika
import pika.exceptions
from opentelemetry import context as otel_context
from opentelemetry import trace
from opentelemetry.propagate import extract

from event_relay import metrics
from event_relay.delivery import Outcome, combine, deliver
from event_relay.envelope import EnvelopeParseError, parse_envelope
from event_relay.routing import plan_deliveries
from event_relay.settings import BRIDGED_ROUTING_KEYS, Settings

logger = logging.getLogger("event_relay.consumer")
tracer = trace.get_tracer("event_relay")

_PUMP_INTERVAL_SECONDS = 5.0


class RelayConsumer:
    def __init__(self, settings: Settings, http_client: httpx.Client | None = None) -> None:
        self._settings = settings
        self._http = http_client or httpx.Client(timeout=settings.relay_http_timeout_seconds)
        self._owns_http = http_client is None
        self._connection: pika.BlockingConnection | None = None
        self._channel: pika.adapters.blocking_connection.BlockingChannel | None = None

    # -- lifecycle ---------------------------------------------------------------

    def run_forever(self) -> None:
        """Connect, consume, and reconnect on any broker-level failure. Only a
        KeyboardInterrupt / SystemExit breaks out.
        """
        while True:
            try:
                self._connect()
                self._pump()
            except (pika.exceptions.AMQPError, OSError) as exc:
                logger.warning("action=relay_broker_error error=%s reconnecting_in=%ss", exc, self._settings.relay_connect_retry_seconds)
                metrics.record_broker_error()
                self._close()
                # The relay is alive and doing its job (retrying the broker) — keep the
                # liveness heartbeat fresh so k8s does not CrashLoop-kill a pod that is
                # only waiting on RabbitMQ to come back.
                self._touch_heartbeat()
                metrics.record_poll()
                time.sleep(self._settings.relay_connect_retry_seconds)
            except (KeyboardInterrupt, SystemExit):
                logger.info("action=relay_shutdown")
                self._close()
                return

    def _connect(self) -> None:
        s = self._settings
        params = pika.ConnectionParameters(
            host=s.rabbitmq_host, port=s.rabbitmq_port, virtual_host=s.rabbitmq_vhost,
            credentials=pika.PlainCredentials(s.rabbitmq_username, s.rabbitmq_password),
            heartbeat=120, blocked_connection_timeout=60, connection_attempts=3, retry_delay=3,
        )
        self._connection = pika.BlockingConnection(params)
        channel = self._connection.channel()

        channel.exchange_declare(exchange=s.rabbitmq_exchange, exchange_type="topic", durable=True)
        channel.exchange_declare(exchange=s.rabbitmq_dlx, exchange_type="topic", durable=True)
        channel.queue_declare(
            queue=s.relay_queue, durable=True,
            arguments={
                "x-dead-letter-exchange": s.rabbitmq_dlx,
                "x-dead-letter-routing-key": s.relay_dlq_routing_key,
            },
        )
        channel.queue_declare(queue=s.relay_dlq_routing_key, durable=True)
        channel.queue_bind(queue=s.relay_dlq_routing_key, exchange=s.rabbitmq_dlx, routing_key=s.relay_dlq_routing_key)
        for routing_key in BRIDGED_ROUTING_KEYS:
            channel.queue_bind(queue=s.relay_queue, exchange=s.rabbitmq_exchange, routing_key=routing_key)

        channel.basic_qos(prefetch_count=s.relay_prefetch)
        channel.basic_consume(queue=s.relay_queue, on_message_callback=self._on_message, auto_ack=False)
        self._channel = channel
        self._touch_heartbeat()
        logger.info(
            "action=relay_connected exchange=%s queue=%s dlq=%s bindings=%s",
            s.rabbitmq_exchange, s.relay_queue, s.relay_dlq_routing_key, list(BRIDGED_ROUTING_KEYS),
        )

    def _pump(self) -> None:
        assert self._connection is not None
        while True:
            self._connection.process_data_events(time_limit=_PUMP_INTERVAL_SECONDS)
            self._touch_heartbeat()
            metrics.record_poll()  # traffic-independent liveness signal for the alert

    def _close(self) -> None:
        try:
            if self._connection is not None and self._connection.is_open:
                self._connection.close()
        except (pika.exceptions.AMQPError, OSError):
            pass
        self._connection = None
        self._channel = None
        if self._owns_http:
            self._http.close()

    # -- message handling ------------------------------------------------------

    def _on_message(self, channel, method, properties, body: bytes) -> None:
        self._touch_heartbeat()
        # Continue the producer's trace if it stamped a W3C `traceparent` header on the
        # AMQP message; otherwise this span roots a fresh trace. Either way the outgoing
        # HTTP POSTs carry a traceparent (delivery.deliver injects the current context),
        # so "relay -> agent-runtime / tool-gateway / memory-knowledge" is one trace.
        parent = extract(_amqp_headers(properties))
        token = otel_context.attach(parent)
        try:
            with tracer.start_as_current_span(
                "event_relay.consume",
                kind=trace.SpanKind.CONSUMER,
                attributes={
                    "messaging.system": "rabbitmq",
                    "messaging.destination": getattr(method, "routing_key", "") or "",
                    "messaging.operation": "process",
                },
            ) as span:
                # The trace id — the producer's if it stamped a `traceparent`, else this
                # span's fresh one — on every settlement log line, so "which trace does
                # this delivery belong to" is answerable straight from the relay logs
                # (and matches delivery.py's own `action=relay_delivery trace_id=...`).
                trace_id = trace.format_trace_id(span.get_span_context().trace_id)
                self._handle(channel, method, body, span, trace_id)
        finally:
            otel_context.detach(token)

    def _handle(self, channel, method, body: bytes, span, trace_id: str) -> None:
        try:
            envelope = parse_envelope(body, routing_key=getattr(method, "routing_key", None))
        except EnvelopeParseError as exc:
            logger.error(
                "action=relay_poison_envelope raw_len=%s trace_id=%s error=%s",
                len(body or b""), trace_id, exc,
            )
            span.set_attribute("relay.outcome", "poison")
            span.set_status(trace.Status(trace.StatusCode.ERROR, "poison envelope"))
            metrics.record_message("unparseable", "poison")
            metrics.record_dlq("poison_envelope")
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        span.set_attribute("event.id", envelope.event_id)
        span.set_attribute("event.type", envelope.event_type)
        span.set_attribute("event.producer", envelope.producer)

        deliveries = plan_deliveries(envelope)
        if not deliveries:
            logger.info(
                "action=relay_no_delivery event_id=%s event_type=%s trace_id=%s reason=%s",
                envelope.event_id, envelope.event_type, trace_id,
                "no workflowInstanceId/toolRequestId in payload or unbridged event type",
            )
            span.set_attribute("relay.outcome", "no_delivery")
            metrics.record_message(envelope.event_type, "no_delivery")
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        logger.info(
            "action=relay_consumed event_id=%s event_type=%s producer=%s trace_id=%s deliveries=%s",
            envelope.event_id, envelope.event_type, envelope.producer, trace_id, len(deliveries),
        )
        span.set_attribute("relay.deliveries", len(deliveries))
        outcomes = [
            deliver(
                self._http, self._settings.base_urls, d,
                event_id=envelope.event_id,
                max_attempts=self._settings.relay_max_attempts,
                backoff_seconds=self._settings.relay_backoff_seconds,
            )
            for d in deliveries
        ]
        settled = combine(outcomes)
        logger.info(
            "action=relay_settled event_id=%s trace_id=%s outcome=%s deliveries=%s acked=%s retried=%s dlq=%s",
            envelope.event_id, trace_id, settled.value, len(outcomes),
            sum(o is Outcome.ACK for o in outcomes),
            sum(o is Outcome.RETRY for o in outcomes),
            sum(o is Outcome.DLQ for o in outcomes),
        )
        span.set_attribute("relay.outcome", settled.value)
        metrics.record_message(envelope.event_type, settled.value)

        if settled is Outcome.RETRY:
            time.sleep(self._settings.relay_requeue_delay_seconds)
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
        elif settled is Outcome.DLQ:
            span.set_status(trace.Status(trace.StatusCode.ERROR, "downstream rejected the transformed body"))
            metrics.record_dlq("delivery_rejected")
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        else:
            channel.basic_ack(delivery_tag=method.delivery_tag)

    # -- health -------------------------------------------------------------

    def _touch_heartbeat(self) -> None:
        try:
            path = self._settings.relay_heartbeat_file
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(str(time.time()))
        except OSError as exc:  # pragma: no cover — never fatal
            logger.debug("heartbeat touch failed: %s", exc)


def _amqp_headers(properties) -> dict[str, str]:
    """W3C-propagation carrier from a pika BasicProperties.headers dict (may be None)."""
    headers = getattr(properties, "headers", None) or {}
    return {str(k).lower(): str(v) for k, v in headers.items()}
