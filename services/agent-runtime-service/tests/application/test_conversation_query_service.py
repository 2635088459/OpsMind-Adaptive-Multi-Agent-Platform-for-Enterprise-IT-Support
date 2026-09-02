"""SPEC-ARO-042 (phase-10 Conversational Intake): ConversationQueryService."""

from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.commands import StartConversationCommand
from agentruntime.application.exceptions import (
    ConversationAccessDeniedException,
    ConversationNotFoundException,
)
from agentruntime.application.records import CreatedTicketRef
from agentruntime.application.services.conversation_query import (
    ConversationQueryService,
)
from agentruntime.application.services.coordinate_agent_tasks import (
    CoordinateAgentTasksService,
)
from agentruntime.application.services.start_conversation import (
    StartConversationService,
)
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.ids import (
    IdempotencyKey,
    TicketCycleId,
    TicketId,
    WorkflowInstanceId,
)
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
    def create_ticket(self, forwarded_bearer_token: str, idempotency_key: str) -> CreatedTicketRef:
        return CreatedTicketRef(
            ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()), version=0, display_id="INC-3000",
        )


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
    start_conversation_service = StartConversationService(
        _FakeTicketWorkflowClient(), start_workflow_service, command_idempotency_repository, clock,
    )
    query_service = ConversationQueryService(workflow_instance_repository)
    return start_conversation_service, query_service, clock


def test_find_conversation_returns_the_real_state_for_its_own_owner(wiring) -> None:
    start_conversation_service, query_service, _clock = wiring
    conversation = start_conversation_service.start_conversation(StartConversationCommand(
        requester_subject="employee-1", forwarded_bearer_token="jwt", idempotency_key=IdempotencyKey("conv-q-1"),
    ))

    view = query_service.find_conversation(conversation.conversation_id, "employee-1")

    assert view.conversation_id == conversation.conversation_id
    assert view.state is WorkflowState.RUNNING
    assert view.started_at == conversation.started_at


def test_find_conversation_denies_a_different_employee(wiring) -> None:
    start_conversation_service, query_service, _clock = wiring
    conversation = start_conversation_service.start_conversation(StartConversationCommand(
        requester_subject="employee-1", forwarded_bearer_token="jwt", idempotency_key=IdempotencyKey("conv-q-2"),
    ))

    with pytest.raises(ConversationAccessDeniedException):
        query_service.find_conversation(conversation.conversation_id, "employee-2")


def test_find_conversation_for_an_unknown_id_is_not_found(wiring) -> None:
    _, query_service, _clock = wiring

    with pytest.raises(ConversationNotFoundException):
        query_service.find_conversation(WorkflowInstanceId.new_id(), "employee-1")


def test_find_most_recent_conversation_returns_the_newest_one(wiring) -> None:
    start_conversation_service, query_service, clock = wiring
    start_conversation_service.start_conversation(StartConversationCommand(
        requester_subject="employee-3", forwarded_bearer_token="jwt", idempotency_key=IdempotencyKey("conv-q-3"),
    ))
    clock.advance(timedelta(seconds=1))  # so "most recent" is unambiguous
    second = start_conversation_service.start_conversation(StartConversationCommand(
        requester_subject="employee-3", forwarded_bearer_token="jwt", idempotency_key=IdempotencyKey("conv-q-4"),
    ))

    view = query_service.find_most_recent_conversation("employee-3")

    assert view.conversation_id == second.conversation_id


def test_find_most_recent_conversation_for_a_requester_with_none_is_not_found(wiring) -> None:
    _, query_service, _clock = wiring

    with pytest.raises(ConversationNotFoundException):
        query_service.find_most_recent_conversation("employee-with-no-conversations")
