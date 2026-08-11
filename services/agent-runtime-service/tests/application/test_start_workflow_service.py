from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import StartWorkflowCommand, TaskNodeInput, WorkflowDefinitionInput
from agentruntime.application.exceptions import DuplicateActiveWorkflowInstanceException, IdempotencyKeyReusedException
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.ids import IdempotencyKey, TicketCycleId, TicketId
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    service = StartWorkflowService(
        workflow_instance_repository, checkpoint_repository, outbox_repository, command_idempotency_repository, clock,
        coordinate_agent_tasks_service,
    )
    return service, workflow_instance_repository, agent_task_repository, outbox_repository, checkpoint_repository


def _command(ticket_id: uuid.UUID, ticket_cycle_id: uuid.UUID, idempotency_key: str = "start-1") -> StartWorkflowCommand:
    definition = WorkflowDefinitionInput(
        "triage-v1", 1, "TICKET_TRIAGE",
        (TaskNodeInput("collect", "collect_diagnostics", frozenset(), "ALL_SUCCESS"),),
    )
    return StartWorkflowCommand(TicketId(ticket_id), TicketCycleId(ticket_cycle_id), definition, IdempotencyKey(idempotency_key))


def test_starts_a_workflow_instance_at_version_one_and_materializes_the_first_runnable_task(wiring) -> None:
    service, _, agent_task_repository, outbox_repository, checkpoint_repository = wiring

    view = service.start(_command(uuid.uuid4(), uuid.uuid4()))

    assert view.state is WorkflowState.RUNNING
    assert view.workflow_version == 1
    assert len(outbox_repository.recorded()) == 1
    assert outbox_repository.recorded()[0].event_type == "agent_runtime.workflow.started"

    tasks = agent_task_repository.find_by_workflow_instance_id(view.workflow_instance_id)
    assert [task.task_key for task in tasks] == ["collect"]

    checkpoints = checkpoint_repository.find_by_workflow_instance_id(view.workflow_instance_id)
    assert [c.type.name for c in checkpoints] == ["STARTED"]


def test_rejects_a_second_active_instance_for_the_same_ticket_cycle_and_workflow_type(wiring) -> None:
    service = wiring[0]
    ticket_id = uuid.uuid4()
    ticket_cycle_id = uuid.uuid4()
    service.start(_command(ticket_id, ticket_cycle_id, "start-1"))

    with pytest.raises(DuplicateActiveWorkflowInstanceException):
        service.start(_command(ticket_id, ticket_cycle_id, "start-2"))


def test_allows_a_new_instance_for_a_different_ticket_cycle(wiring) -> None:
    service = wiring[0]
    ticket_id = uuid.uuid4()
    service.start(_command(ticket_id, uuid.uuid4(), "start-1"))

    second = service.start(_command(ticket_id, uuid.uuid4(), "start-2"))

    assert second.state is WorkflowState.RUNNING


def test_duplicate_start_with_the_same_key_returns_the_cached_result_without_creating_a_second_instance(wiring) -> None:
    service, workflow_instance_repository, _, outbox_repository, _ = wiring
    ticket_id = uuid.uuid4()
    ticket_cycle_id = uuid.uuid4()
    first = service.start(_command(ticket_id, ticket_cycle_id, "start-1"))

    second = service.start(_command(ticket_id, ticket_cycle_id, "start-1"))

    assert second.workflow_instance_id == first.workflow_instance_id
    assert len(outbox_repository.recorded()) == 1


def test_start_with_a_reused_key_but_different_request_is_rejected(wiring) -> None:
    service = wiring[0]
    ticket_id = uuid.uuid4()
    service.start(_command(ticket_id, uuid.uuid4(), "start-1"))

    with pytest.raises(IdempotencyKeyReusedException):
        service.start(_command(ticket_id, uuid.uuid4(), "start-1"))
