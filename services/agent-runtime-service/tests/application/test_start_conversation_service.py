"""SPEC-ARO-038 (phase-10 Conversational Intake): StartConversationService."""

from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import StartConversationCommand
from agentruntime.application.exceptions import IdempotencyKeyReusedException, TicketCreationFailedException
from agentruntime.application.records import CreatedTicketRef
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.start_conversation import StartConversationService
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.domain.ids import IdempotencyKey, TicketCycleId, TicketId
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


class _FakeTicketWorkflowClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []
        self.next_ref = CreatedTicketRef(
            ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()), version=0, display_id="INC-1000",
        )
        self.fail = False

    def create_ticket(self, forwarded_bearer_token: str, idempotency_key: str) -> CreatedTicketRef:
        self.calls.append((forwarded_bearer_token, idempotency_key))
        if self.fail:
            raise TicketCreationFailedException("simulated downstream failure")
        return self.next_ref


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    start_workflow_service = StartWorkflowService(
        workflow_instance_repository, checkpoint_repository, outbox_repository, command_idempotency_repository, clock,
        coordinate_agent_tasks_service, telemetry, audit_recorder,
    )
    ticket_workflow_client = _FakeTicketWorkflowClient()
    service = StartConversationService(ticket_workflow_client, start_workflow_service, command_idempotency_repository, clock)
    return service, ticket_workflow_client, workflow_instance_repository


def _command(requester_subject: str = "employee-1", idempotency_key: str = "conv-1") -> StartConversationCommand:
    return StartConversationCommand(
        requester_subject=requester_subject, forwarded_bearer_token="employee-jwt-abc", idempotency_key=IdempotencyKey(idempotency_key),
    )


def test_starts_a_conversation_by_creating_a_real_ticket_then_a_workflow_instance(wiring) -> None:
    service, ticket_workflow_client, workflow_instance_repository = wiring

    view = service.start_conversation(_command())

    assert view.conversation_id is not None
    assert ticket_workflow_client.calls == [("employee-jwt-abc", "conv-1")]

    record = workflow_instance_repository.find_by_id(view.conversation_id)
    assert record is not None
    assert str(record.workflow_type) == "conversational_intake"
    assert record.requester_subject == "employee-1"
    assert record.ticket_id == ticket_workflow_client.next_ref.ticket_id
    assert record.ticket_cycle_id == ticket_workflow_client.next_ref.ticket_cycle_id


def test_a_repeated_idempotency_key_never_calls_create_ticket_twice(wiring) -> None:
    service, ticket_workflow_client, _ = wiring

    first = service.start_conversation(_command())
    second = service.start_conversation(_command())

    assert first.conversation_id == second.conversation_id
    assert len(ticket_workflow_client.calls) == 1


def test_a_reused_idempotency_key_with_a_different_requester_is_rejected(wiring) -> None:
    service, _, _ = wiring
    service.start_conversation(_command(requester_subject="employee-1", idempotency_key="conv-2"))

    with pytest.raises(IdempotencyKeyReusedException):
        service.start_conversation(_command(requester_subject="employee-2", idempotency_key="conv-2"))


def test_a_failed_ticket_creation_never_creates_a_workflow_instance(wiring) -> None:
    service, ticket_workflow_client, workflow_instance_repository = wiring
    ticket_workflow_client.fail = True

    with pytest.raises(TicketCreationFailedException):
        service.start_conversation(_command(idempotency_key="conv-fail-1"))

    assert workflow_instance_repository.find_non_terminal(10) == []
