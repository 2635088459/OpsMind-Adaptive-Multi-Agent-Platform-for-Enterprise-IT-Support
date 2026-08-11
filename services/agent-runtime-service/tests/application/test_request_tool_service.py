from __future__ import annotations

import pytest

from agentruntime.application.commands import RequestToolCommand
from agentruntime.application.exceptions import IdempotencyKeyReusedException
from agentruntime.application.records import ToolDispatchAcknowledgement, ToolRequestRecord
from agentruntime.application.services.request_tool import RequestToolService
from agentruntime.domain.enums import ToolRequestStatus
from agentruntime.domain.ids import AgentTaskId, IdempotencyKey, WorkflowInstanceId
from agentruntime.infrastructure.persistence.in_memory import InMemoryCheckpointRepository, InMemoryCommandIdempotencyRepository, InMemoryToolRequestRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


class RecordingToolGatewayPort:
    def __init__(self) -> None:
        self.dispatched: list[ToolRequestRecord] = []

    def dispatch(self, request: ToolRequestRecord) -> ToolDispatchAcknowledgement:
        self.dispatched.append(request)
        return ToolDispatchAcknowledgement(request.id, ToolRequestStatus.DISPATCHED, request.created_at)


@pytest.fixture
def wiring():
    checkpoint_repository = InMemoryCheckpointRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    tool_gateway_port = RecordingToolGatewayPort()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    service = RequestToolService(checkpoint_repository, tool_request_repository, tool_gateway_port, command_idempotency_repository, clock)
    return service, checkpoint_repository, tool_gateway_port


def _command(workflow_instance_id: WorkflowInstanceId, agent_task_id: AgentTaskId, idempotency_key: str = "tool-1") -> RequestToolCommand:
    return RequestToolCommand(
        workflow_instance_id, agent_task_id, '{"before":"restart"}', "restart_service", '{"service":"api"}', IdempotencyKey(idempotency_key)
    )


def test_dispatching_a_tool_request_persists_a_checkpoint_first(wiring) -> None:
    """02-business-invariants §"Checkpoint Invariants" / §"Tool Gateway Boundary": a
    checkpoint must be persisted before ToolGatewayPort is ever called.
    """
    service, checkpoint_repository, tool_gateway_port = wiring
    workflow_instance_id = WorkflowInstanceId.new_id()
    agent_task_id = AgentTaskId.new_id()

    view = service.request_tool(_command(workflow_instance_id, agent_task_id))

    assert view.status is ToolRequestStatus.DISPATCHED
    checkpoints = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert len(checkpoints) == 1

    assert len(tool_gateway_port.dispatched) == 1
    dispatched_request = tool_gateway_port.dispatched[0]
    assert dispatched_request.preceding_checkpoint_id == checkpoints[0].id


def test_duplicate_request_with_the_same_key_does_not_dispatch_twice(wiring) -> None:
    """09-concurrency-and-idempotency §"Command Idempotency": without this, a retried
    request would write a second Checkpoint and dispatch the tool twice.
    """
    service, checkpoint_repository, tool_gateway_port = wiring
    workflow_instance_id = WorkflowInstanceId.new_id()
    agent_task_id = AgentTaskId.new_id()
    command = _command(workflow_instance_id, agent_task_id, "tool-1")

    first = service.request_tool(command)
    second = service.request_tool(command)

    assert second.tool_request_id == first.tool_request_id
    assert len(tool_gateway_port.dispatched) == 1
    assert len(checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)) == 1


def test_request_with_a_reused_key_but_different_request_is_rejected(wiring) -> None:
    service = wiring[0]
    workflow_instance_id = WorkflowInstanceId.new_id()
    service.request_tool(_command(workflow_instance_id, AgentTaskId.new_id(), "tool-1"))

    with pytest.raises(IdempotencyKeyReusedException):
        service.request_tool(_command(workflow_instance_id, AgentTaskId.new_id(), "tool-1"))
