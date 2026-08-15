from __future__ import annotations

import pytest

from agentruntime.application.records import ToolDispatchAcknowledgement, ToolRequestRecord
from agentruntime.application.services.dispatch_tool_requests import DispatchToolRequestsService
from agentruntime.domain.enums import ToolRequestStatus
from agentruntime.domain.ids import AgentTaskId, CheckpointId, ToolRequestId, WorkflowInstanceId
from agentruntime.infrastructure.persistence.in_memory import InMemoryToolRequestRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


class RecordingToolGatewayPort:
    def __init__(self) -> None:
        self.dispatched: list[ToolRequestRecord] = []

    def dispatch(self, request: ToolRequestRecord) -> ToolDispatchAcknowledgement:
        self.dispatched.append(request)
        return ToolDispatchAcknowledgement(request.id, ToolRequestStatus.DISPATCHED, request.created_at)


def _pending_tool_request(clock, **overrides) -> ToolRequestRecord:
    now = clock.now()
    defaults = dict(
        id=ToolRequestId.new_id(), workflow_instance_id=WorkflowInstanceId.new_id(), agent_task_id=AgentTaskId.new_id(),
        preceding_checkpoint_id=CheckpointId.new_id(), tool_name="restart_service", request_payload="{}",
        status=ToolRequestStatus.PENDING, created_at=now, updated_at=now,
    )
    defaults.update(overrides)
    return ToolRequestRecord(**defaults)


def test_dispatches_pending_tool_requests_and_marks_them_dispatched() -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6: this is
    the one place ToolGatewayPort is ever actually called — outside of and after
    RequestToolService's own transaction.
    """
    tool_request_repository = InMemoryToolRequestRepository()
    tool_gateway_port = RecordingToolGatewayPort()
    clock = FakeClock()
    service = DispatchToolRequestsService(tool_request_repository, tool_gateway_port, clock)
    record = tool_request_repository.save(_pending_tool_request(clock))

    report = service.dispatch_pending_requests()

    assert report.scanned == 1
    assert report.dispatched == 1
    assert len(tool_gateway_port.dispatched) == 1
    assert tool_gateway_port.dispatched[0].id == record.id
    assert tool_request_repository.find_by_id(record.id).status is ToolRequestStatus.DISPATCHED


def test_only_pending_tool_requests_are_scanned() -> None:
    tool_request_repository = InMemoryToolRequestRepository()
    tool_gateway_port = RecordingToolGatewayPort()
    clock = FakeClock()
    service = DispatchToolRequestsService(tool_request_repository, tool_gateway_port, clock)
    tool_request_repository.save(_pending_tool_request(clock, status=ToolRequestStatus.DISPATCHED))
    tool_request_repository.save(_pending_tool_request(clock, status=ToolRequestStatus.COMPLETED))
    still_pending = tool_request_repository.save(_pending_tool_request(clock))

    report = service.dispatch_pending_requests()

    assert report.scanned == 1
    assert len(tool_gateway_port.dispatched) == 1
    assert tool_gateway_port.dispatched[0].id == still_pending.id


def test_dispatch_respects_the_batch_size() -> None:
    tool_request_repository = InMemoryToolRequestRepository()
    tool_gateway_port = RecordingToolGatewayPort()
    clock = FakeClock()
    service = DispatchToolRequestsService(tool_request_repository, tool_gateway_port, clock)
    for _ in range(3):
        tool_request_repository.save(_pending_tool_request(clock))

    report = service.dispatch_pending_requests(batch_size=2)

    assert report.scanned == 2
    assert report.dispatched == 2
    assert len(tool_request_repository.find_pending(10)) == 1


def test_no_pending_tool_requests_is_a_clean_no_op() -> None:
    tool_request_repository = InMemoryToolRequestRepository()
    tool_gateway_port = RecordingToolGatewayPort()
    clock = FakeClock()
    service = DispatchToolRequestsService(tool_request_repository, tool_gateway_port, clock)

    report = service.dispatch_pending_requests()

    assert report.scanned == 0
    assert report.dispatched == 0
    assert tool_gateway_port.dispatched == []
