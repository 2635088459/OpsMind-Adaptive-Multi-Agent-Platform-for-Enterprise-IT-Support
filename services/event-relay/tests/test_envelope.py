from __future__ import annotations

import uuid

import pytest

from event_relay.envelope import (
    EnvelopeParseError,
    coerce_uuid,
    parse_envelope,
    require_uuid,
    uuid_exact,
)


def test_parses_a_governance_granted_envelope(make_envelope):
    wf = str(uuid.uuid4())
    body = make_envelope(
        "approval.granted.v1",
        {"approvalRequestId": "ar-1", "workflowInstanceId": wf, "toolRequestId": None, "decidedBy": "alice"},
        ticketId=str(uuid.uuid4()),
    )
    env = parse_envelope(body)
    assert env.event_type == "approval.granted.v1"
    assert env.payload["workflowInstanceId"] == wf
    assert env.producer == "policy-approval-governance-service"
    assert env.schema_version == 1


def test_payload_may_arrive_as_a_json_string(make_envelope):
    body = make_envelope(
        "approval.denied.v1",
        {"approvalRequestId": "ar-2", "toolRequestId": str(uuid.uuid4()), "decidedBy": "bob", "reason": "nope"},
        payload_as_string=True,
    )
    env = parse_envelope(body)
    assert env.payload["reason"] == "nope"


def test_causation_id_falls_back_to_event_id(make_envelope):
    body = make_envelope("approval.granted.v1", {"workflowInstanceId": str(uuid.uuid4())}, eventId="evt-9")
    env = parse_envelope(body)
    assert env.causation_id is None  # not present on the wire
    assert env.correlation_id  # correlationId was present


@pytest.mark.parametrize("bad", [b"", b"not json", b"[1,2,3]", b"{}", b'{"eventId":"x"}'])
def test_rejects_unusable_bodies(bad):
    with pytest.raises(EnvelopeParseError):
        parse_envelope(bad)


def test_coerce_uuid_passes_through_a_real_uuid():
    real = str(uuid.uuid4())
    assert coerce_uuid(real, "correlation") == real


def test_coerce_uuid_derives_a_stable_uuid_from_a_non_uuid():
    first = coerce_uuid("sched-42", "correlation")
    second = coerce_uuid("sched-42", "correlation")
    assert first == second
    assert uuid.UUID(first)  # valid
    assert first != coerce_uuid("sched-43", "correlation")


def test_require_uuid_never_returns_none():
    assert uuid.UUID(require_uuid(None, "ticket"))


def test_uuid_exact_is_none_for_a_non_uuid():
    assert uuid_exact("not-a-uuid") is None
    real = str(uuid.uuid4())
    assert uuid_exact(real) == real
