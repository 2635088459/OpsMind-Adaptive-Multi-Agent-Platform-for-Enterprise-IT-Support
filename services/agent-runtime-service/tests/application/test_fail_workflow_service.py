from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import FailWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import DefinitionVersion, IdempotencyKey, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.infrastructure.persistence.in_memory import InMemoryCommandIdempotencyRepository, InMemoryOutboxRepository, InMemoryWorkflowInstanceRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    service = FailWorkflowService(workflow_instance_repository, outbox_repository, command_idempotency_repository, clock)

    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_id, outbox_repository


def test_fails_a_running_workflow_and_publishes_the_failed_event_with_reason(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring

    view = service.fail(FailWorkflowCommand(workflow_instance_id, IdempotencyKey("fail-1"), "tool exhausted retries"))

    assert view.state is WorkflowState.FAILED
    assert view.workflow_version == 2
    [record] = outbox_repository.recorded()
    assert record.event_type == "agent_runtime.workflow.failed"
    assert "tool exhausted retries" in record.payload


def test_fail_command_rejects_a_blank_reason() -> None:
    with pytest.raises(ValueError):
        FailWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("fail-x"), "   ")


def test_duplicate_fail_with_the_same_key_returns_the_cached_result_without_publishing_again(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring
    service.fail(FailWorkflowCommand(workflow_instance_id, IdempotencyKey("fail-1"), "tool exhausted retries"))

    second = service.fail(FailWorkflowCommand(workflow_instance_id, IdempotencyKey("fail-1"), "tool exhausted retries"))

    assert second.state is WorkflowState.FAILED
    assert len(outbox_repository.recorded()) == 1


def test_fail_with_a_different_key_while_already_failed_is_rejected(wiring) -> None:
    service, workflow_instance_id = wiring[0], wiring[1]
    service.fail(FailWorkflowCommand(workflow_instance_id, IdempotencyKey("fail-1"), "tool exhausted retries"))

    with pytest.raises(InvalidWorkflowStateException):
        service.fail(FailWorkflowCommand(workflow_instance_id, IdempotencyKey("fail-2"), "different reason"))


def test_failing_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.fail(FailWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("fail-x"), "unreachable"))
