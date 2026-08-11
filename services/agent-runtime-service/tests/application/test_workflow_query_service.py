from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from agentruntime.application.exceptions import CheckpointNotFoundException, WorkflowInstanceNotFoundException
from agentruntime.application.records import CheckpointRecord, WorkflowInstanceRecord
from agentruntime.application.services.workflow_query import WorkflowQueryService
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.ids import (
    CheckpointId,
    DefinitionVersion,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.in_memory import InMemoryCheckpointRepository, InMemoryWorkflowInstanceRepository

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)


def _workflow_instance(ticket_id: TicketId, ticket_cycle_id: TicketCycleId | None = None, state: WorkflowState = WorkflowState.RUNNING) -> WorkflowInstanceRecord:
    return WorkflowInstanceRecord(
        id=WorkflowInstanceId.new_id(), ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id or TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=state, workflow_version=1, pause_generation=0,
        created_at=NOW, updated_at=NOW,
    )


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    service = WorkflowQueryService(workflow_instance_repository, checkpoint_repository)
    return service, workflow_instance_repository, checkpoint_repository


def test_finds_a_workflow_instance_by_id(wiring) -> None:
    service, workflow_instance_repository, _ = wiring
    record = _workflow_instance(TicketId(uuid.uuid4()))
    workflow_instance_repository.save(record)

    view = service.find_workflow_instance(record.id)

    assert view.workflow_instance_id == record.id
    assert view.state is WorkflowState.RUNNING


def test_finding_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.find_workflow_instance(WorkflowInstanceId.new_id())


def test_finds_every_workflow_instance_for_a_ticket_across_cycles_and_terminal_states(wiring) -> None:
    service, workflow_instance_repository, _ = wiring
    ticket_id = TicketId(uuid.uuid4())
    first_cycle = _workflow_instance(ticket_id, state=WorkflowState.COMPLETED)
    second_cycle = _workflow_instance(ticket_id, state=WorkflowState.RUNNING)
    other_ticket = _workflow_instance(TicketId(uuid.uuid4()))
    workflow_instance_repository.save(first_cycle)
    workflow_instance_repository.save(second_cycle)
    workflow_instance_repository.save(other_ticket)

    views = service.find_workflow_instances_by_ticket(ticket_id)

    assert {view.workflow_instance_id for view in views} == {first_cycle.id, second_cycle.id}


def test_a_ticket_with_no_workflow_instances_returns_an_empty_list_not_an_error(wiring) -> None:
    service = wiring[0]

    views = service.find_workflow_instances_by_ticket(TicketId(uuid.uuid4()))

    assert views == []


def test_finds_the_latest_checkpoint_by_recorded_at(wiring) -> None:
    service, workflow_instance_repository, checkpoint_repository = wiring
    record = _workflow_instance(TicketId(uuid.uuid4()))
    workflow_instance_repository.save(record)
    older = CheckpointRecord(CheckpointId.new_id(), record.id, CheckpointType.STARTED, 1, "{}", NOW)
    newer = CheckpointRecord(CheckpointId.new_id(), record.id, CheckpointType.PRE_TOOL_CALL, 1, "{}", NOW + timedelta(minutes=5))
    checkpoint_repository.save(older)
    checkpoint_repository.save(newer)

    view = service.find_latest_checkpoint(record.id)

    assert view.checkpoint_id == newer.id
    assert view.type is CheckpointType.PRE_TOOL_CALL


def test_finding_the_latest_checkpoint_for_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.find_latest_checkpoint(WorkflowInstanceId.new_id())


def test_finding_the_latest_checkpoint_for_an_instance_with_none_recorded_is_rejected(wiring) -> None:
    service, workflow_instance_repository, _ = wiring
    record = _workflow_instance(TicketId(uuid.uuid4()))
    workflow_instance_repository.save(record)

    with pytest.raises(CheckpointNotFoundException):
        service.find_latest_checkpoint(record.id)
