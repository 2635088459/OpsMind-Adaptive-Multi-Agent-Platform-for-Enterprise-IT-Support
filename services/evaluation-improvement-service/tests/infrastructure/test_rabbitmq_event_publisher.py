"""SPEC-XREL-001 (+ SPEC-XOBS-001 Part C fix): RabbitMqEventPublisherAdapter publishes
the OutboxRecord's payload — which application.outbox_codec.build_outbox_record has
ALREADY assembled into the full 06-event-contracts envelope — verbatim, on the
event_type routing key, and never raises for an ordinary delivery failure
(DispatchOutboxEventsService reads a returned False as "retry with backoff").
"""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime

import pika.exceptions
import pytest

from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.domain.events import ImprovementPromoted
from evaluationimprovement.domain.ids import CandidateId
from evaluationimprovement.infrastructure.messaging.rabbitmq_publisher import RabbitMqEventPublisherAdapter


def _record():
    candidate_id = CandidateId(uuid.uuid4())
    return build_outbox_record(
        ImprovementPromoted(
            candidate_id=candidate_id, candidate_type="PROMPT", target_component="triage-prompt",
            promoted_version="v7", proposed_change={"text": "be more specific"},
            occurred_at=datetime(2026, 9, 9, 12, 0, tzinfo=UTC),
        ),
        "improvement.promoted.v1", aggregate_id=str(candidate_id),
        occurred_at=datetime(2026, 9, 9, 12, 0, tzinfo=UTC),
        correlation_id=to_correlation_id(str(uuid.uuid4())),
    )


class _FakeChannel:
    def __init__(self) -> None:
        self.published: list[dict] = []

    def exchange_declare(self, **_kwargs) -> None:
        pass

    def basic_publish(self, *, exchange, routing_key, body, properties) -> None:
        self.published.append({"exchange": exchange, "routing_key": routing_key, "body": body, "properties": properties})


class _BoomChannel(_FakeChannel):
    def basic_publish(self, **_kwargs) -> None:
        raise pika.exceptions.AMQPConnectionError("broker down")


def _adapter() -> RabbitMqEventPublisherAdapter:
    return RabbitMqEventPublisherAdapter(
        host="localhost", port=5672, username="guest", password="guest", vhost="/", exchange="opsmind.events",
    )


def test_publish_emits_the_prebuilt_envelope_verbatim_on_the_event_type_routing_key(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = _adapter()
    channel = _FakeChannel()
    monkeypatch.setattr(adapter, "_ensure_channel", lambda: channel)

    record = _record()
    assert adapter.publish(record) is True

    sent = channel.published[0]
    assert sent["exchange"] == "opsmind.events"
    assert sent["routing_key"] == "improvement.promoted.v1"
    assert sent["properties"].message_id == str(record.outbox_id)
    assert sent["properties"].content_type == "application/json"

    # Body is exactly record.payload — the already-assembled envelope, not re-wrapped.
    assert sent["body"].decode("utf-8") == record.payload
    envelope = json.loads(sent["body"])
    assert envelope["eventType"] == "improvement.promoted.v1"
    assert envelope["producer"] == "evaluation-improvement-service"
    assert envelope["payload"]["promoted_version"] == "v7"
    assert envelope["payload"]["target_component"] == "triage-prompt"
    assert envelope["payload"]["proposed_change"] == {"text": "be more specific"}
    # Not double-nested:
    assert "payload" not in envelope["payload"]


def test_publish_returns_false_instead_of_raising_on_a_broker_error(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = _adapter()
    monkeypatch.setattr(adapter, "_ensure_channel", lambda: _BoomChannel())
    reset_calls: list[int] = []
    monkeypatch.setattr(adapter, "_reset_connection", lambda: reset_calls.append(1))

    assert adapter.publish(_record()) is False
    assert reset_calls == [1]
