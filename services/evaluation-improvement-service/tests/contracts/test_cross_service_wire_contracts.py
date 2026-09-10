"""SPEC-XOBS-001 Part C: eval-improvement is a producer of the execute-case request and
the improvement.promoted envelope, and a consumer of agent-runtime's execute-case
response. Pinned against the shared `contracts/` fixtures.

The execute-case request below is the exact seam the `verificationCondition` dict-vs-str
bug came from: this service sends `test_case.verification_condition` (a dict) and
agent-runtime must accept an object there.
"""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest

from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.events import ImprovementPromoted
from evaluationimprovement.domain.ids import CandidateId, DatasetId, RunId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.infrastructure.runtime.agent_runtime_client import HttpAgentRuntimeEvaluationAdapter

pytestmark = pytest.mark.unit

CONTRACTS = Path(__file__).resolve().parents[4] / "contracts"


def _fixture(name: str) -> dict:
    path = CONTRACTS / name
    assert path.is_file(), f"missing contract fixture: {path}"
    return json.loads(path.read_text())


def _test_case() -> EvaluationTestCase:
    return EvaluationTestCase.create(
        TestCaseId.new_id(), DatasetId.new_id(), "pw-reset-forgot", "forgot password", "cannot log in",
        {}, {"classification": "SELF_SERVICE_ACTION"}, ("send_password_reset",),
        ("identity.user.resetPassword",), False, {}, Criticality.STANDARD,
    )


def test_the_execute_case_request_this_service_sends_matches_the_contract_shape():
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json=_fixture("agent-runtime-execute-evaluation-case-response.json"))

    adapter = HttpAgentRuntimeEvaluationAdapter(httpx.Client(transport=httpx.MockTransport(handler)), "http://agent-runtime")
    adapter.execute_case(RunId.new_id(), "triage-prompt@v3", _test_case(), 1)

    contract_keys = set(_fixture("agent-runtime-execute-evaluation-case-request.json"))
    assert set(captured) == contract_keys
    assert isinstance(captured["verificationCondition"], dict)   # regression guard
    assert isinstance(captured["mockSystemState"], dict)


def test_this_service_reads_the_execute_case_response_contract_including_the_token_split():
    fixture = _fixture("agent-runtime-execute-evaluation-case-response.json")

    adapter = HttpAgentRuntimeEvaluationAdapter(
        httpx.Client(transport=httpx.MockTransport(lambda _r: httpx.Response(200, json=fixture))),
        "http://agent-runtime",
    )
    result = adapter.execute_case(RunId.new_id(), "triage-prompt@v3", _test_case(), 1)

    assert result.final_state == fixture["finalState"]
    assert result.classification == fixture["classification"]
    assert result.cost_tokens == fixture["costTokens"]
    assert result.prompt_tokens == fixture["promptTokens"]
    assert result.completion_tokens == fixture["completionTokens"]
    assert result.latency_ms == fixture["latencyMs"]


def test_improvement_promoted_envelope_matches_the_published_contract():
    fixture = _fixture("improvement-promoted-v1.json")
    candidate_id = CandidateId(uuid.UUID(fixture["candidateId"]))

    record = build_outbox_record(
        ImprovementPromoted(
            candidate_id=candidate_id,
            candidate_type=fixture["payload"]["candidate_type"],
            target_component=fixture["payload"]["target_component"],
            promoted_version=fixture["payload"]["promoted_version"],
            proposed_change=fixture["payload"]["proposed_change"],
            occurred_at=datetime(2026, 9, 9, 14, 0, tzinfo=UTC),
        ),
        "improvement.promoted.v1", aggregate_id=str(candidate_id),
        occurred_at=datetime(2026, 9, 9, 14, 0, tzinfo=UTC),
        correlation_id=to_correlation_id(fixture["correlationId"]),
    )
    envelope = json.loads(record.payload)

    assert set(envelope) == set(fixture)                      # same envelope keys
    assert envelope["eventType"] == "improvement.promoted.v1"
    assert set(envelope["payload"]) == set(fixture["payload"])  # same inner payload keys
