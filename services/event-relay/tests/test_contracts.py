"""SPEC-XOBS-001 Part C: the event-relay is a pure consumer of governance's approval
envelopes and a pure producer of the agent-runtime / tool-gateway HTTP event bodies.
This pins both ends against the shared `contracts/` fixtures — the exact seam the two
runtime bugs (verificationCondition type, side_effect_kind persistence) came from.
"""

from __future__ import annotations

import json
from pathlib import Path

from event_relay.envelope import parse_envelope
from event_relay.routing import (
    AGENT_EVENTS_PATH,
    TG_APPROVAL_DENIED_PATH,
    TG_APPROVAL_GRANTED_PATH,
    plan_deliveries,
)

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"


def _fixture(name: str) -> dict:
    path = CONTRACTS / name
    assert path.is_file(), f"missing contract fixture: {path}"
    return json.loads(path.read_text())


def test_contracts_directory_is_present():
    assert CONTRACTS.is_dir(), f"expected the shared contracts dir at {CONTRACTS}"


def test_granted_envelope_maps_to_the_runtime_event_request_contract():
    env = parse_envelope(json.dumps(_fixture("governance-approval-granted-v1.json")).encode())
    deliveries = plan_deliveries(env)

    agent = next(d for d in deliveries if d.target == "agent-runtime")
    assert agent.path == AGENT_EVENTS_PATH
    # The body the relay POSTs must carry exactly the keys agent-runtime's
    # RuntimeEventRequest declares (see agent-runtime-runtime-event-request.json).
    expected_keys = set(_fixture("agent-runtime-runtime-event-request.json"))
    assert set(agent.json_body) == expected_keys
    inner = json.loads(agent.json_body["payload"])
    assert set(inner) == {"approvalRequestId", "decision", "approvedBy"}
    assert inner["decision"] == "APPROVED"

    gateway = next(d for d in deliveries if d.target == "tool-gateway")
    assert gateway.path == TG_APPROVAL_GRANTED_PATH
    tg_contract_keys = set(_fixture("tool-gateway-approval-granted-event-request.json"))
    # The relay may also send `constraints`; every contract key must be present.
    assert tg_contract_keys <= set(gateway.json_body)
    assert isinstance(gateway.json_body["constraints"], dict)


def test_denied_envelope_carries_denied_by_and_reason():
    env = parse_envelope(json.dumps(_fixture("governance-approval-denied-v1.json")).encode())
    gateway = next(d for d in plan_deliveries(env) if d.target == "tool-gateway")
    assert gateway.path == TG_APPROVAL_DENIED_PATH
    assert gateway.json_body["denied_by"]
    assert "denial_reason" in gateway.json_body
    agent = next(d for d in plan_deliveries(env) if d.target == "agent-runtime")
    assert json.loads(agent.json_body["payload"])["decision"] == "DENIED"


def test_expired_envelope_only_reaches_agent_runtime_as_decision_expired():
    env = parse_envelope(json.dumps(_fixture("governance-approval-expired-v1.json")).encode())
    deliveries = plan_deliveries(env)
    assert [d.target for d in deliveries] == ["agent-runtime"]
    assert json.loads(deliveries[0].json_body["payload"])["decision"] == "EXPIRED"


def test_improvement_promoted_envelope_is_forwarded_with_its_payload_keys_intact():
    fixture = _fixture("improvement-promoted-v1.json")
    env = parse_envelope(json.dumps(fixture).encode())
    delivery = plan_deliveries(env)[0]
    assert delivery.target == "agent-runtime"
    assert delivery.path.endswith("/events/improvement-promoted")
    assert delivery.json_body["eventType"] == "improvement.promoted.v1"
    assert set(delivery.json_body["payload"]) == set(fixture["payload"])


def test_ticket_resolved_source_envelope_is_forwarded_to_memory_knowledge_verbatim():
    fixture = _fixture("memory-knowledge-ticket-resolved-source.json")
    # The versioned type lives only on the AMQP routing key (as in the real system).
    env = parse_envelope(json.dumps(fixture).encode(), routing_key="ticket.resolved.v1")
    delivery = plan_deliveries(env)[0]
    assert delivery.target == "memory-knowledge"
    assert delivery.path == "/internal/memory/v1/events/ticket-resolved"
    # forwarded verbatim: memory-knowledge's own model-validator unwraps this shape.
    assert delivery.json_body["eventType"] == fixture["eventType"]
    assert set(delivery.json_body["payload"]) == set(fixture["payload"])
