"""SPEC-EI-026 (policy-approval-release-contract) 14-testing-strategy: "Policy Approval
contract 有 mock/integration 测试" — exercises HttpPolicyApprovalAdapter against
httpx.MockTransport, never a live 06 instance (see that class's own module docstring
for why).
"""

from __future__ import annotations

import json

import httpx
import pytest

from evaluationimprovement.application.exceptions import PolicyApprovalUnavailableException
from evaluationimprovement.domain.ids import CandidateId
from evaluationimprovement.infrastructure.policy.policy_approval_client import HttpPolicyApprovalAdapter

_CANDIDATE_ID = CandidateId.new_id()


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.mark.unit
def test_request_approval_maps_a_successful_response_and_sends_the_generic_approval_type() -> None:
    captured_request = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured_request["body"] = json.loads(request.content)
        captured_request["headers"] = request.headers
        return httpx.Response(201, json={
            "approvalRequestId": "approval-123", "requestKey": f"evaluation-improvement:candidate:{_CANDIDATE_ID}",
            "sourceDomain": "evaluation-improvement", "sourceRequestId": str(_CANDIDATE_ID), "status": "REQUESTED",
            "approvalType": "GENERIC", "riskLevel": "MEDIUM", "constraints": [], "createdAt": "2026-08-28T00:00:00Z",
            "updatedAt": "2026-08-28T00:00:00Z",
        })

    adapter = HttpPolicyApprovalAdapter(_client(handler), "http://policy-approval:8006/", "svc-token-1")
    ref = adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "MEDIUM", "author-1")

    assert ref.approval_request_id == "approval-123"
    assert ref.status == "REQUESTED"

    body = captured_request["body"]
    assert body["approvalType"] == "GENERIC"
    assert body["riskLevel"] == "MEDIUM"
    assert body["sourceDomain"] == "evaluation-improvement"
    assert body["sourceRequestId"] == str(_CANDIDATE_ID)
    assert body["requestKey"] == f"evaluation-improvement:candidate:{_CANDIDATE_ID}"
    assert body["requestHash"]
    assert captured_request["headers"]["authorization"] == "Bearer svc-token-1"


@pytest.mark.unit
def test_request_approval_omits_the_auth_header_when_no_service_token_is_configured() -> None:
    captured_request = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured_request["headers"] = request.headers
        return httpx.Response(201, json={"approvalRequestId": "approval-456", "status": "REQUESTED"})

    adapter = HttpPolicyApprovalAdapter(_client(handler), "http://policy-approval:8006")
    adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "LOW", "author-1")

    assert "authorization" not in captured_request["headers"]


@pytest.mark.unit
def test_a_resubmitted_request_for_the_same_candidate_carries_the_same_request_key() -> None:
    seen_keys = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_keys.append(json.loads(request.content)["requestKey"])
        return httpx.Response(201, json={"approvalRequestId": "approval-789", "status": "REQUESTED"})

    adapter = HttpPolicyApprovalAdapter(_client(handler), "http://policy-approval:8006")
    adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "HIGH", "author-1")
    adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "HIGH", "author-1")

    assert seen_keys[0] == seen_keys[1]


@pytest.mark.unit
def test_request_approval_wraps_a_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    adapter = HttpPolicyApprovalAdapter(_client(handler), "http://policy-approval:8006")
    with pytest.raises(PolicyApprovalUnavailableException):
        adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "MEDIUM", "author-1")


@pytest.mark.unit
def test_request_approval_wraps_a_non_2xx_status() -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # noqa: ARG001
        return httpx.Response(503, json={"error": "policy approval unavailable"})

    adapter = HttpPolicyApprovalAdapter(_client(handler), "http://policy-approval:8006")
    with pytest.raises(PolicyApprovalUnavailableException):
        adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "MEDIUM", "author-1")


@pytest.mark.unit
def test_request_approval_wraps_a_malformed_response_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # noqa: ARG001
        # Missing the required "approvalRequestId"/"status" keys.
        return httpx.Response(201, json={"requestKey": "k1"})

    adapter = HttpPolicyApprovalAdapter(_client(handler), "http://policy-approval:8006")
    with pytest.raises(PolicyApprovalUnavailableException):
        adapter.request_approval(_CANDIDATE_ID, "identity-agent-prompt", "MEDIUM", "author-1")
