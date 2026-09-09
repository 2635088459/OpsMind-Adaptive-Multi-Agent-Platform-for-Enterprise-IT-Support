"""phase-05 (tool-gateway-mediation): HttpToolGatewayPort against httpx.MockTransport —
submit + synchronous execute against tool-integration-gateway's Runtime API, never a
live instance.
"""

from __future__ import annotations

import json

import httpx
import pytest

from agentruntime.application.records import ToolRequestRecord
from agentruntime.domain.enums import ToolRequestStatus
from agentruntime.domain.ids import AgentTaskId, CheckpointId, ToolRequestId, WorkflowInstanceId
from agentruntime.infrastructure.tool_gateway import HttpToolGatewayPort
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit

_CAP = "identity.user.sendPasswordResetLink"


def _record(payload: str = '{"summary": "send a reset link"}') -> ToolRequestRecord:
    now = FakeClock().now()
    return ToolRequestRecord(
        id=ToolRequestId.new_id(), workflow_instance_id=WorkflowInstanceId.new_id(), agent_task_id=AgentTaskId.new_id(),
        preceding_checkpoint_id=CheckpointId.new_id(), tool_name="self_service_action", request_payload=payload,
        status=ToolRequestStatus.PENDING, created_at=now, updated_at=now,
    )


def _port(handler) -> HttpToolGatewayPort:  # noqa: ANN001
    return HttpToolGatewayPort(
        "http://tool-gateway:8020", FakeClock(), _CAP, http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


def test_queued_then_executed_completed_carries_the_result() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(f"{request.method} {request.url.path}")
        assert request.headers["X-Caller-Type"] == "SERVICE"
        if request.url.path.endswith("/execute"):
            return httpx.Response(200, json={
                "tool_request_id": "gw-1", "status": "COMPLETED", "result_envelope_id": "env-1",
                "output": {"resetLinkSent": True},
            })
        body = json.loads(request.content)
        assert body["capability_name"] == _CAP
        assert body["input_payload"] == {"summary": "send a reset link"}
        return httpx.Response(200, json={"tool_request_id": "gw-1", "status": "QUEUED"})

    record = _record()
    ack = _port(handler).dispatch(record)

    assert ack.tool_request_id == record.id
    assert ack.status is ToolRequestStatus.COMPLETED
    assert json.loads(ack.result_payload) == {"resetLinkSent": True}
    assert calls == [
        "POST /internal/tool-gateway/v1/tool-requests",
        "POST /internal/tool-gateway/v1/tool-requests/gw-1/execute",
    ]


def test_execute_terminal_failed_maps_to_failed_with_a_reason() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/execute"):
            return httpx.Response(200, json={
                "tool_request_id": "gw-2", "status": "TERMINAL_FAILED", "result_envelope_id": None,
                "output": None, "failure_reason": "connector unavailable",
            })
        return httpx.Response(200, json={"tool_request_id": "gw-2", "status": "QUEUED"})

    ack = _port(handler).dispatch(_record())
    assert ack.status is ToolRequestStatus.FAILED
    assert ack.failure_reason == "connector unavailable"


def test_a_non_queued_submit_status_stays_dispatched_and_does_not_execute() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return httpx.Response(200, json={"tool_request_id": "gw-3", "status": "WAITING_APPROVAL"})

    ack = _port(handler).dispatch(_record())
    assert ack.status is ToolRequestStatus.DISPATCHED
    assert all(not p.endswith("/execute") for p in calls)


def test_a_gateway_error_fails_open_to_dispatched() -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # noqa: ARG001
        return httpx.Response(503, json={"error": "unavailable"})

    ack = _port(handler).dispatch(_record())
    assert ack.status is ToolRequestStatus.DISPATCHED
    assert ack.result_payload is None and ack.failure_reason is None
