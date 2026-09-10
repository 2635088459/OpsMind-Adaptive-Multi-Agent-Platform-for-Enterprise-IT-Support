"""SPEC-XREL-001 §Observability: the relay propagates W3C trace context into its HTTP
fan-out and continues an inbound traceparent stamped on the AMQP message.
"""

from __future__ import annotations

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

# Bind the OTel proxy tracer to a provider we own BEFORE event_relay modules capture
# their module-level `trace.get_tracer(...)` — otherwise the proxy latches onto
# whatever provider happens to exist at first span creation.
_EXPORTER = InMemorySpanExporter()
_PROVIDER = TracerProvider()
_PROVIDER.add_span_processor(SimpleSpanProcessor(_EXPORTER))
trace.set_tracer_provider(_PROVIDER)

import json
import uuid
from types import SimpleNamespace

import httpx
import pytest

from event_relay.consumer import RelayConsumer, _amqp_headers
from event_relay.delivery import deliver
from event_relay.routing import Delivery
from event_relay.settings import Settings

_BASE_URLS = {"agent-runtime": "http://agent-runtime:8000"}


@pytest.fixture(autouse=True)
def _clear_spans():
    _EXPORTER.clear()
    yield


def _delivery() -> Delivery:
    return Delivery(target="agent-runtime", path="/internal/agent-runtime/v1/events",
                    json_body={"event_id": "e1"}, describe="t", correlation_id="c1")


def test_deliver_injects_a_traceparent_header_for_the_downstream_service():
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(request.headers)
        return httpx.Response(200)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=2, backoff_seconds=0)

    assert outcome.value == "ack"
    assert "traceparent" in seen  # W3C context injected onto the POST
    spans = _EXPORTER.get_finished_spans()
    span = next(s for s in spans if s.name == "event_relay.deliver agent-runtime")
    assert span.kind is trace.SpanKind.CLIENT
    assert span.attributes["relay.target"] == "agent-runtime"


def test_deliver_span_carries_the_outcome_and_status_and_the_call_never_raises():
    with httpx.Client(transport=httpx.MockTransport(lambda _r: httpx.Response(404))) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=1, backoff_seconds=0)
    assert outcome.value == "ack"  # 404 is an "understood rejection"
    span = next(s for s in _EXPORTER.get_finished_spans() if s.name.startswith("event_relay.deliver"))
    assert span.attributes["relay.delivery_outcome"] == "ack"
    assert span.attributes["http.status_code"] == 404


def test_deliver_dlq_outcome_marks_the_span_error():
    with httpx.Client(transport=httpx.MockTransport(lambda _r: httpx.Response(400))) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=1, backoff_seconds=0)
    assert outcome.value == "dlq"
    span = next(s for s in _EXPORTER.get_finished_spans() if s.name.startswith("event_relay.deliver"))
    assert span.status.status_code is trace.StatusCode.ERROR


def test_amqp_headers_carrier_handles_missing_and_present_headers():
    assert _amqp_headers(SimpleNamespace(headers=None)) == {}
    assert _amqp_headers(SimpleNamespace()) == {}

    carrier = _amqp_headers(SimpleNamespace(headers={"traceparent": "00-abc-def-01", "eventType": "approval.granted.v1"}))
    assert carrier["traceparent"] == "00-abc-def-01"
    assert carrier["eventtype"] == "approval.granted.v1"  # lower-cased for the propagator


class _FakeChannel:
    def __init__(self) -> None:
        self.acked: list[int] = []
        self.nacked: list[tuple[int, bool]] = []

    def basic_ack(self, delivery_tag: int) -> None:
        self.acked.append(delivery_tag)

    def basic_nack(self, delivery_tag: int, requeue: bool) -> None:
        self.nacked.append((delivery_tag, requeue))


def test_inbound_amqp_traceparent_is_continued_through_to_the_downstream_http_post(tmp_path):
    """The end-to-end propagation SPEC-XREL-001 §Observability promises: a `traceparent`
    stamped on the AMQP message becomes the trace of the consume span, the deliver
    span, AND the `traceparent` header on the HTTP POST to the downstream service —
    one trace across the AMQP -> relay -> HTTP hop.
    """
    trace_id_hex = "0af7651916cd43dd8448eb211c80319c"  # W3C spec example
    inbound_traceparent = f"00-{trace_id_hex}-b7ad6b7169203331-01"

    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(request.headers)
        return httpx.Response(200, json={"applied": True})

    settings = Settings(relay_max_attempts=1, relay_backoff_seconds=0.0,
                        relay_heartbeat_file=str(tmp_path / "alive"))
    client = httpx.Client(transport=httpx.MockTransport(handler))
    consumer = RelayConsumer(settings, http_client=client)

    body = json.dumps({
        "eventId": str(uuid.uuid4()), "eventType": "improvement.promoted.v1",
        "producer": "evaluation-improvement-service", "schemaVersion": 1,
        "aggregateId": "cand-1", "correlationId": str(uuid.uuid4()),
        "occurredAt": "2026-09-10T12:00:00Z",
        "payload": {"candidate_id": "cand-1", "candidate_type": "PROMPT_CHANGE",
                    "target_component": "conversation_reasoning_prompt",
                    "promoted_version": "v-test", "proposed_change": {"system_prompt": "x"}},
    }).encode()
    channel = _FakeChannel()
    method = SimpleNamespace(routing_key="improvement.promoted.v1", delivery_tag=7)
    properties = SimpleNamespace(headers={"traceparent": inbound_traceparent})

    consumer._on_message(channel, method, properties, body)
    client.close()

    # the message settled cleanly...
    assert channel.acked == [7]
    # ...the POST carried a traceparent continuing the SAME trace...
    assert "traceparent" in seen
    assert seen["traceparent"].split("-")[1] == trace_id_hex
    # ...and both relay spans belong to that trace.
    want = int(trace_id_hex, 16)
    by_name = {s.name: s for s in _EXPORTER.get_finished_spans()}
    assert by_name["event_relay.consume"].context.trace_id == want
    assert by_name["event_relay.deliver agent-runtime"].context.trace_id == want
