"""SPEC-EI-012 (agent-runtime-evaluation-client-contract) 14-testing-strategy: "Agent
Runtime evaluation contract 有 mock/integration 测试" — exercises
HttpAgentRuntimeEvaluationAdapter against httpx.MockTransport, never a live 03
instance (see that class's own module docstring for why).
"""

from __future__ import annotations

import json

import httpx
import pytest

from evaluationimprovement.domain.enums import CaseExecutionStatus, Criticality
from evaluationimprovement.domain.ids import DatasetId, RunId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.infrastructure.runtime.agent_runtime_client import (
    AgentRuntimeEvaluationUnavailableError,
    HttpAgentRuntimeEvaluationAdapter,
)

_RUN_ID = RunId.new_id()


def _test_case() -> EvaluationTestCase:
    return EvaluationTestCase.create(
        TestCaseId.new_id(), DatasetId.new_id(), "k1", "Duo enrollment expired", "mfa broken",
        {"duoStatus": "EXPIRED"}, {"classification": "MFA_ENROLLMENT_EXPIRED"}, ("reset_duo_enrollment",),
        ("disable_mfa",), False, {"duoStatus": "ACTIVE"}, Criticality.CRITICAL,
    )


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.mark.unit
def test_execute_case_maps_a_successful_response_and_never_leaks_ground_truth() -> None:
    captured_request = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured_request["body"] = json.loads(request.content)
        return httpx.Response(200, json={
            "finalState": "RESOLVED", "toolCalls": ["reset_duo_enrollment"], "classification": "MFA_ENROLLMENT_EXPIRED",
            "policyViolationCount": 0, "forbiddenToolCallCount": 0, "unauthorizedMemoryAccessCount": 0,
            "costTokens": 250, "latencyMs": 1200, "workflowTraceRef": "trace-abc",
        })

    adapter = HttpAgentRuntimeEvaluationAdapter(_client(handler), "http://agent-runtime:8003/")
    test_case = _test_case()
    result = adapter.execute_case(_RUN_ID, "agent-runtime:rc1", test_case, 1)

    assert result.status is CaseExecutionStatus.COMPLETED
    assert result.final_state == "RESOLVED"
    assert result.tool_calls == ("reset_duo_enrollment",)
    assert result.classification == "MFA_ENROLLMENT_EXPIRED"
    assert result.cost_tokens == 250
    assert result.workflow_trace_ref == "trace-abc"

    # Deliberately never sends the answer key — see HttpAgentRuntimeEvaluationAdapter's
    # own module docstring.
    assert "groundTruth" not in captured_request["body"]
    assert captured_request["body"]["caseKey"] == "k1"
    assert captured_request["body"]["runGeneration"] == 1


@pytest.mark.unit
def test_execute_case_wraps_a_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    adapter = HttpAgentRuntimeEvaluationAdapter(_client(handler), "http://agent-runtime:8003")
    with pytest.raises(AgentRuntimeEvaluationUnavailableError):
        adapter.execute_case(_RUN_ID, "agent-runtime:rc1", _test_case(), 1)


@pytest.mark.unit
def test_execute_case_wraps_a_non_2xx_status() -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # noqa: ARG001
        return httpx.Response(503, json={"error": "agent runtime unavailable"})

    adapter = HttpAgentRuntimeEvaluationAdapter(_client(handler), "http://agent-runtime:8003")
    with pytest.raises(AgentRuntimeEvaluationUnavailableError):
        adapter.execute_case(_RUN_ID, "agent-runtime:rc1", _test_case(), 1)


@pytest.mark.unit
def test_execute_case_wraps_a_malformed_response_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # noqa: ARG001
        # Missing the required "finalState"/"classification" keys.
        return httpx.Response(200, json={"toolCalls": []})

    adapter = HttpAgentRuntimeEvaluationAdapter(_client(handler), "http://agent-runtime:8003")
    with pytest.raises(AgentRuntimeEvaluationUnavailableError):
        adapter.execute_case(_RUN_ID, "agent-runtime:rc1", _test_case(), 1)
