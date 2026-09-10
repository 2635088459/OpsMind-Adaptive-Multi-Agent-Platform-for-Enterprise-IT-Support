from __future__ import annotations

import json
import uuid

import pytest

from event_relay.envelope import parse_envelope
from event_relay.routing import (
    AGENT_EVENTS_PATH,
    AGENT_IMPROVEMENT_PATH,
    TG_APPROVAL_DENIED_PATH,
    TG_APPROVAL_GRANTED_PATH,
    plan_deliveries,
)


def _plan(make_envelope, event_type, payload, **overrides):
    return plan_deliveries(parse_envelope(make_envelope(event_type, payload, **overrides)))


def test_granted_with_both_linkage_ids_fans_out_to_two_targets(make_envelope):
    wf, tr = str(uuid.uuid4()), str(uuid.uuid4())
    deliveries = _plan(
        make_envelope, "approval.granted.v1",
        {"approvalRequestId": "ar-1", "workflowInstanceId": wf, "toolRequestId": tr, "decidedBy": "alice"},
        ticketId=str(uuid.uuid4()),
    )
    by_target = {d.target: d for d in deliveries}
    assert set(by_target) == {"agent-runtime", "tool-gateway"}

    ar = by_target["agent-runtime"]
    assert ar.path == AGENT_EVENTS_PATH
    assert ar.json_body["event_type"] == "approval.granted.v1"
    assert ar.json_body["workflow_instance_id"] == wf
    assert uuid.UUID(ar.json_body["correlation_id"])  # coerced to a real uuid
    inner = json.loads(ar.json_body["payload"])
    assert inner == {"approvalRequestId": "ar-1", "decision": "APPROVED", "approvedBy": "alice"}

    tg = by_target["tool-gateway"]
    assert tg.path == TG_APPROVAL_GRANTED_PATH
    assert tg.json_body["tool_request_id"] == tr
    assert tg.json_body["approved_by"] == "alice"
    assert tg.json_body["approval_request_id"] == "ar-1"


def test_granted_with_only_a_workflow_id_hits_agent_runtime_only(make_envelope):
    deliveries = _plan(
        make_envelope, "approval.granted.v1",
        {"approvalRequestId": "ar-1", "workflowInstanceId": str(uuid.uuid4()), "toolRequestId": None, "decidedBy": "x"},
    )
    assert [d.target for d in deliveries] == ["agent-runtime"]


def test_denied_uses_the_denied_endpoint_and_carries_the_reason(make_envelope):
    tr = str(uuid.uuid4())
    deliveries = _plan(
        make_envelope, "approval.denied.v1",
        {"approvalRequestId": "ar-2", "workflowInstanceId": str(uuid.uuid4()), "toolRequestId": tr,
         "decidedBy": "bob", "reason": "insufficient justification"},
    )
    tg = next(d for d in deliveries if d.target == "tool-gateway")
    assert tg.path == TG_APPROVAL_DENIED_PATH
    assert tg.json_body["denied_by"] == "bob"
    assert tg.json_body["denial_reason"] == "insufficient justification"

    ar = next(d for d in deliveries if d.target == "agent-runtime")
    assert json.loads(ar.json_body["payload"])["decision"] == "DENIED"


def test_expired_only_reaches_agent_runtime_even_with_a_tool_request_id(make_envelope):
    deliveries = _plan(
        make_envelope, "approval.expired.v1",
        {"approvalRequestId": "ar-3", "workflowInstanceId": str(uuid.uuid4()), "toolRequestId": str(uuid.uuid4()),
         "expiresAt": "2026-09-09T11:00:00Z"},
    )
    assert [d.target for d in deliveries] == ["agent-runtime"]
    assert json.loads(deliveries[0].json_body["payload"])["decision"] == "EXPIRED"


def test_non_uuid_workflow_instance_id_is_skipped_not_synthesized(make_envelope):
    deliveries = _plan(
        make_envelope, "approval.granted.v1",
        {"approvalRequestId": "ar-1", "workflowInstanceId": "legacy-key-7", "toolRequestId": None, "decidedBy": "x"},
    )
    assert deliveries == []


def test_improvement_promoted_forwards_the_raw_envelope(make_envelope):
    payload = {
        "candidate_id": "cand-1", "candidate_type": "PROMPT", "target_component": "triage-prompt",
        "promoted_version": "v3", "proposed_change": {"text": "..."},
    }
    body = make_envelope("improvement.promoted.v1", payload, producer="evaluation-improvement-service")
    deliveries = plan_deliveries(parse_envelope(body))
    assert len(deliveries) == 1
    d = deliveries[0]
    assert d.target == "agent-runtime"
    assert d.path == AGENT_IMPROVEMENT_PATH
    assert d.json_body["eventType"] == "improvement.promoted.v1"
    assert d.json_body["payload"]["candidate_id"] == "cand-1"


def test_unbridged_event_type_plans_nothing(make_envelope):
    assert plan_deliveries(parse_envelope(make_envelope("ticket.created.v1", {"ticketId": "x"}))) == []


@pytest.mark.parametrize(
    ("event_type", "endpoint"),
    [
        ("ticket.resolved.v1", "/internal/memory/v1/events/ticket-resolved"),
        ("ticket.closed.v1", "/internal/memory/v1/events/ticket-closed"),
        ("workflow.completed.v1", "/internal/memory/v1/events/workflow-completed"),
        ("workflow.failed.v1", "/internal/memory/v1/events/workflow-failed"),
    ],
)
def test_memory_source_events_forward_the_raw_envelope_to_memory_knowledge(make_envelope, event_type, endpoint):
    body = make_envelope(
        event_type,
        {"resolutionCycleId": str(uuid.uuid4()), "resolutionCode": "FIXED", "resolutionSummary": "did the thing",
         "resolvedBy": "support.agent", "resolvedAt": "2026-09-09T12:00:00Z"},
        producer="ticket-workflow-service",
    )
    deliveries = plan_deliveries(parse_envelope(body))
    assert len(deliveries) == 1
    d = deliveries[0]
    assert d.target == "memory-knowledge"
    assert d.path == endpoint
    # forwarded verbatim — memory-knowledge's own @model_validator unwraps the envelope
    assert d.json_body["eventType"] == event_type
    assert "payload" in d.json_body


def test_ticketworkflow_unversioned_event_type_is_matched_via_the_amqp_routing_key(make_ticketworkflow_envelope):
    # ticket-workflow serializes `eventType: "ticket.resolved"` (unversioned) with no
    # routingKey in the body; the versioned form arrives only as the AMQP routing key.
    body = make_ticketworkflow_envelope("ticket.resolved", {
        "resolutionCycleId": str(uuid.uuid4()), "resolutionCode": "FIXED",
        "resolutionSummary": "cleared the print spooler", "resolvedBy": "support.agent",
        "resolvedAt": "2026-09-09T12:00:00Z",
    })

    # Without the routing key the relay cannot tell this is a bridged event...
    assert plan_deliveries(parse_envelope(body)) == []
    # ...but the consumer always passes method.routing_key, and then it routes.
    deliveries = plan_deliveries(parse_envelope(body, routing_key="ticket.resolved.v1"))
    assert [d.target for d in deliveries] == ["memory-knowledge"]
    assert deliveries[0].path == "/internal/memory/v1/events/ticket-resolved"
