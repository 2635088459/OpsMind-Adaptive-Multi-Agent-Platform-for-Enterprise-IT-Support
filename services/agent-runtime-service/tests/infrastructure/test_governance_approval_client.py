"""SPEC-ARO-040 (phase-10 Conversational Intake): HttpGovernanceApprovalClient,
exercised against httpx.MockTransport, never a live 06-policy-approval-governance
instance.
"""

from __future__ import annotations

import json
import uuid

import httpx
import pytest

from agentruntime.application.exceptions import GovernanceApprovalRequestFailedException
from agentruntime.domain.ids import AgentTaskId, TicketId, WorkflowInstanceId
from agentruntime.infrastructure.governance_approval_client import (
    HttpGovernanceApprovalClient,
)

pytestmark = pytest.mark.unit

_AGENT_TASK_ID = AgentTaskId.new_id()
_WORKFLOW_INSTANCE_ID = WorkflowInstanceId.new_id()
_TICKET_ID = TicketId(uuid.uuid4())


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


class _FakeTokenProvider:
    def get_token(self) -> str:
        return "service-identity-token"


def test_request_approval_sends_the_real_shape_and_authenticates_via_the_service_identity() -> None:
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={
            "approvalRequestId": "approval-123", "requestKey": f"agent-runtime:action:{_AGENT_TASK_ID}",
            "sourceDomain": "agent-runtime", "sourceRequestId": str(_AGENT_TASK_ID), "status": "REQUESTED",
            "approvalType": "TOOL_EXECUTION", "riskLevel": "HIGH", "constraints": [],
            "createdAt": "2026-09-01T00:00:00Z", "updatedAt": "2026-09-01T00:00:00Z",
        })

    client = HttpGovernanceApprovalClient("http://policy-approval:8080", _client(handler), token_provider=_FakeTokenProvider())

    ref = client.request_approval(_AGENT_TASK_ID, _WORKFLOW_INSTANCE_ID, _TICKET_ID, "HIGH", "needs a human")

    assert ref.approval_request_id == "approval-123"
    assert ref.status == "REQUESTED"
    assert captured["url"] == "http://policy-approval:8080/api/v1/approval-requests"
    assert captured["headers"]["authorization"] == "Bearer service-identity-token"
    body = captured["body"]
    assert body["approvalType"] == "TOOL_EXECUTION"
    assert body["riskLevel"] == "HIGH"
    assert body["sourceDomain"] == "agent-runtime"
    assert body["sourceRequestId"] == str(_AGENT_TASK_ID)
    assert body["workflowInstanceId"] == str(_WORKFLOW_INSTANCE_ID)
    assert body["ticketId"] == str(_TICKET_ID)
    assert body["requestKey"] == f"agent-runtime:action:{_AGENT_TASK_ID}"
    assert body["requestHash"]


def test_request_approval_raises_when_no_token_provider_is_configured() -> None:
    client = HttpGovernanceApprovalClient("http://policy-approval:8080", _client(lambda r: httpx.Response(201, json={})))

    with pytest.raises(GovernanceApprovalRequestFailedException):
        client.request_approval(_AGENT_TASK_ID, _WORKFLOW_INSTANCE_ID, _TICKET_ID, "HIGH", "reason")


def test_request_approval_raises_on_a_non_201_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"error": "invalid"})

    client = HttpGovernanceApprovalClient("http://policy-approval:8080", _client(handler), token_provider=_FakeTokenProvider())

    with pytest.raises(GovernanceApprovalRequestFailedException):
        client.request_approval(_AGENT_TASK_ID, _WORKFLOW_INSTANCE_ID, _TICKET_ID, "HIGH", "reason")


def test_request_approval_raises_on_a_network_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    client = HttpGovernanceApprovalClient("http://policy-approval:8080", _client(handler), token_provider=_FakeTokenProvider())

    with pytest.raises(GovernanceApprovalRequestFailedException):
        client.request_approval(_AGENT_TASK_ID, _WORKFLOW_INSTANCE_ID, _TICKET_ID, "HIGH", "reason")


def test_request_approval_raises_on_a_malformed_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(201, json={"unexpected": "shape"})

    client = HttpGovernanceApprovalClient("http://policy-approval:8080", _client(handler), token_provider=_FakeTokenProvider())

    with pytest.raises(GovernanceApprovalRequestFailedException):
        client.request_approval(_AGENT_TASK_ID, _WORKFLOW_INSTANCE_ID, _TICKET_ID, "HIGH", "reason")
