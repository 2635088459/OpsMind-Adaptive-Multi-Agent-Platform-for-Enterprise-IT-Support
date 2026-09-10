"""SPEC-XOBS-001 Part C: agent-runtime is a consumer of the event-relay's runtime-event
body and of eval-improvement's execute-case request, and a producer of the execute-case
response. Pin each against the shared `contracts/` fixtures so a rename/retype on either
side fails here at change time instead of at runtime.

The `verificationCondition` field below is the exact contract the dict-vs-str bug
violated.
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime
from pathlib import Path

import pytest

from agentruntime.interfaces.evaluation.schemas import (
    ExecuteEvaluationCaseRequest,
    ExecuteEvaluationCaseResponse,
)
from agentruntime.interfaces.event.schemas import RuntimeEventRequest

pytestmark = pytest.mark.unit

CONTRACTS = Path(__file__).resolve().parents[4] / "contracts"


def _fixture(name: str) -> dict:
    path = CONTRACTS / name
    assert path.is_file(), f"missing contract fixture: {path}"
    return json.loads(path.read_text())


def test_runtime_event_request_accepts_the_relay_body():
    fixture = _fixture("agent-runtime-runtime-event-request.json")
    model = RuntimeEventRequest(**fixture)

    # The ids the relay coerces to UUID strings must parse as real UUIDs here.
    assert isinstance(model.correlation_id, uuid.UUID)
    assert isinstance(model.causation_id, uuid.UUID)
    assert isinstance(model.ticket_id, uuid.UUID)
    assert isinstance(model.workflow_instance_id, uuid.UUID)
    assert isinstance(model.occurred_at, datetime)
    assert model.schema_version >= 1
    # payload is a stringified JSON object the consumer re-parses.
    assert isinstance(model.payload, str)
    assert set(json.loads(model.payload)) == {"approvalRequestId", "decision", "approvedBy"}


def test_execute_evaluation_case_request_treats_verification_condition_as_an_object():
    fixture = _fixture("agent-runtime-execute-evaluation-case-request.json")
    model = ExecuteEvaluationCaseRequest(**fixture)

    assert isinstance(model.verificationCondition, dict)   # regression guard: never `str`
    assert isinstance(model.mockSystemState, dict)
    assert isinstance(model.allowedTools, list)
    assert model.runId == fixture["runId"]


def test_execute_evaluation_case_response_matches_the_fixture_shape():
    fixture = _fixture("agent-runtime-execute-evaluation-case-response.json")
    model = ExecuteEvaluationCaseResponse(**fixture)
    dumped = model.model_dump()

    # Every key eval-improvement's HttpAgentRuntimeEvaluationAdapter reads must survive
    # a round-trip, including the prompt/completion split alongside the total.
    for key in ("finalState", "classification", "toolCalls", "costTokens", "promptTokens", "completionTokens", "latencyMs"):
        assert key in dumped, key
    assert isinstance(dumped["promptTokens"], int) and isinstance(dumped["completionTokens"], int)
