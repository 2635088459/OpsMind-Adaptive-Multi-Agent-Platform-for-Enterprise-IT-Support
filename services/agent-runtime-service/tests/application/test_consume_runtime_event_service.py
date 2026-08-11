from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from agentruntime.application.commands import RuntimeEventEnvelope
from agentruntime.application.exceptions import StaleRuntimeEventException, WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.consume_runtime_event import ConsumeRuntimeEventService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.ids import CausationId, CorrelationId, DefinitionVersion, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.infrastructure.persistence.in_memory import InMemoryProcessedEventRepository, InMemoryWorkflowInstanceRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    clock = FakeClock()
    service = ConsumeRuntimeEventService(processed_event_repository, workflow_instance_repository, clock)

    workflow_instance_id = WorkflowInstanceId.new_id()
    ticket_id = TicketId(uuid.uuid4())
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, processed_event_repository, workflow_instance_id, ticket_id


def _envelope(workflow_instance_id: WorkflowInstanceId, ticket_id: TicketId, event_id: str, expected_workflow_version: int | None) -> RuntimeEventEnvelope:
    return RuntimeEventEnvelope(
        event_id=event_id, event_type="tool.completed", producer="tool-gateway-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        workflow_instance_id=workflow_instance_id, occurred_at=datetime(2026, 1, 1, tzinfo=UTC), payload="{}",
        expected_workflow_version=expected_workflow_version,
    )


def test_consuming_a_new_matching_event_marks_it_processed(wiring) -> None:
    service, processed_event_repository, workflow_instance_id, ticket_id = wiring

    applied = service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    assert applied is True
    assert processed_event_repository.is_processed("evt-1") is True


def test_consuming_the_same_event_twice_is_a_no_op_the_second_time(wiring) -> None:
    service, _, workflow_instance_id, ticket_id = wiring
    service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    second_applied = service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    assert second_applied is False


def test_a_stale_event_is_rejected_but_still_marked_processed(wiring) -> None:
    service, processed_event_repository, workflow_instance_id, ticket_id = wiring

    with pytest.raises(StaleRuntimeEventException):
        service.consume(_envelope(workflow_instance_id, ticket_id, "evt-2", 99))
    assert processed_event_repository.is_processed("evt-2") is True


def test_an_event_for_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service, _, _, ticket_id = wiring

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.consume(_envelope(WorkflowInstanceId.new_id(), ticket_id, "evt-3", None))
