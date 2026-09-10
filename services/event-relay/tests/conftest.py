from __future__ import annotations

import json
import uuid

import pytest


def _envelope(event_type: str, payload: dict, **overrides: object) -> bytes:
    body = {
        "eventId": overrides.get("eventId", str(uuid.uuid4())),
        "eventType": event_type,
        "producer": overrides.get("producer", "policy-approval-governance-service"),
        "schemaVersion": overrides.get("schemaVersion", 1),
        "aggregateId": overrides.get("aggregateId", str(uuid.uuid4())),
        "correlationId": overrides.get("correlationId", str(uuid.uuid4())),
        "occurredAt": overrides.get("occurredAt", "2026-09-09T12:00:00Z"),
        "payload": payload,
    }
    if "ticketId" in overrides:
        body["ticketId"] = overrides["ticketId"]
    if "causationId" in overrides:
        body["causationId"] = overrides["causationId"]
    if overrides.get("payload_as_string"):
        body["payload"] = json.dumps(payload)
    return json.dumps(body).encode("utf-8")


def _ticketworkflow_envelope(unversioned_event_type: str, payload: dict) -> bytes:
    """The real shape ticket-workflow's RabbitOutboxEventPublisherAdapter serializes:
    `eventType` is the UNVERSIONED name and there is no `routingKey` in the body — the
    versioned form only ever exists as the AMQP routing key.
    """
    body = {
        "eventId": str(uuid.uuid4()),
        "eventType": unversioned_event_type,
        "producer": "ticket-workflow-service",
        "schemaVersion": 1,
        "aggregateId": str(uuid.uuid4()),
        "ticketId": str(uuid.uuid4()),
        "correlationId": str(uuid.uuid4()),
        "occurredAt": "2026-09-09T12:00:00Z",
        "payload": payload,
    }
    return json.dumps(body).encode("utf-8")


@pytest.fixture
def make_envelope():
    return _envelope


@pytest.fixture
def make_ticketworkflow_envelope():
    return _ticketworkflow_envelope
