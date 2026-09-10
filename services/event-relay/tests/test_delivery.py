from __future__ import annotations

import httpx
import pytest

from event_relay.delivery import Outcome, classify, combine, deliver
from event_relay.routing import Delivery

_BASE_URLS = {"agent-runtime": "http://agent-runtime:8000", "tool-gateway": "http://tg:8020"}


def _delivery() -> Delivery:
    return Delivery(
        target="agent-runtime", path="/internal/agent-runtime/v1/events",
        json_body={"event_id": "e1"}, describe="test", correlation_id="c1",
    )


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (200, Outcome.ACK), (202, Outcome.ACK), (204, Outcome.ACK),
        (403, Outcome.ACK), (404, Outcome.ACK), (409, Outcome.ACK), (422, Outcome.ACK),
        (408, Outcome.RETRY), (429, Outcome.RETRY), (500, Outcome.RETRY), (503, Outcome.RETRY),
        (400, Outcome.DLQ), (401, Outcome.DLQ), (405, Outcome.DLQ), (415, Outcome.DLQ),
    ],
)
def test_classify(status, expected):
    assert classify(status) is expected


def test_combine_prioritises_retry_then_dlq_then_ack():
    assert combine([Outcome.ACK, Outcome.RETRY, Outcome.DLQ]) is Outcome.RETRY
    assert combine([Outcome.ACK, Outcome.DLQ]) is Outcome.DLQ
    assert combine([Outcome.ACK, Outcome.ACK]) is Outcome.ACK


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_deliver_acks_on_200():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"applied": True})

    with _client(handler) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=3, backoff_seconds=0)
    assert outcome is Outcome.ACK
    assert len(calls) == 1
    assert calls[0].headers["X-Correlation-Id"] == "c1"


def test_deliver_retries_then_succeeds():
    seq = iter([httpx.Response(503), httpx.Response(200)])

    with _client(lambda _r: next(seq)) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=3, backoff_seconds=0)
    assert outcome is Outcome.ACK


def test_deliver_returns_retry_when_attempts_exhaust():
    with _client(lambda _r: httpx.Response(500)) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=2, backoff_seconds=0)
    assert outcome is Outcome.RETRY


def test_deliver_dead_letters_a_400():
    with _client(lambda _r: httpx.Response(400, json={"error": {"code": "VALIDATION_ERROR"}})) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=3, backoff_seconds=0)
    assert outcome is Outcome.DLQ


def test_deliver_treats_a_transport_error_as_retry():
    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route to host")

    with _client(handler) as client:
        outcome = deliver(client, _BASE_URLS, _delivery(), event_id="e1", max_attempts=2, backoff_seconds=0)
    assert outcome is Outcome.RETRY
