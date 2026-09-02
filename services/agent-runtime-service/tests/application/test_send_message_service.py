"""SPEC-ARO-039/041 (phase-10 Conversational Intake): SendMessageService."""

from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import (
    SendMessageCommand,
    StartConversationCommand,
)
from agentruntime.application.exceptions import (
    ConversationAccessDeniedException,
    ConversationNotFoundException,
    EscalationRoutingNotConfiguredException,
    IdempotencyKeyReusedException,
)
from agentruntime.application.records import (
    CreatedTicketRef,
    KnowledgeSnippet,
    ReasoningOutcome,
    TriagedTicketRef,
)
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import (
    CoordinateAgentTasksService,
)
from agentruntime.application.services.send_message import SendMessageService
from agentruntime.application.services.start_conversation import (
    StartConversationService,
)
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.domain.enums import AgentTaskState, WorkflowState
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
        self.ticket_id = TicketId(uuid.uuid4())
        self.ticket_cycle_id = TicketCycleId(uuid.uuid4())
        self.triage_calls: list[tuple] = []

    def create_ticket(self, forwarded_bearer_token: str, idempotency_key: str) -> CreatedTicketRef:
        return CreatedTicketRef(ticket_id=self.ticket_id, ticket_cycle_id=self.ticket_cycle_id, version=0, display_id="INC-2000")

    def triage_ticket(self, ticket_id, current_version, category_id, support_queue_id, priority, reason, idempotency_key) -> TriagedTicketRef:
        self.triage_calls.append((ticket_id, current_version, category_id, support_queue_id, priority, reason, idempotency_key))
        return TriagedTicketRef(version=current_version + 1)


class _FakeKnowledgeRetrievalPort:
    def __init__(self) -> None:
        self.snippets: list[KnowledgeSnippet] = []

    def search(self, query, workflow_instance_id, requester_subject) -> list[KnowledgeSnippet]:
        return self.snippets


class _FakeConversationReasoningPort:
    def __init__(self) -> None:
        self.next_outcome = ReasoningOutcome(kind="text", text="default reply")

    def decide(self, message_text, knowledge_snippets) -> ReasoningOutcome:
        return self.next_outcome


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
    start_conversation_service = StartConversationService(
        ticket_workflow_client, start_workflow_service, command_idempotency_repository, clock,
    )
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    knowledge_retrieval_port = _FakeKnowledgeRetrievalPort()
    conversation_reasoning_port = _FakeConversationReasoningPort()
    send_message_service = SendMessageService(
        workflow_instance_repository, agent_task_repository, checkpoint_repository, command_idempotency_repository, clock,
        knowledge_retrieval_port, conversation_reasoning_port, ticket_workflow_client, complete_workflow_service,
        escalation_category_id="cat-1", escalation_support_queue_id="queue-1", escalation_priority="MEDIUM",
        escalation_team_name="IT Support",
    )
    conversation = start_conversation_service.start_conversation(StartConversationCommand(
        requester_subject="employee-1", forwarded_bearer_token="employee-jwt", idempotency_key=IdempotencyKey("conv-1"),
    ))
    return {
        "service": send_message_service, "conversation_id": conversation.conversation_id,
        "agent_task_repository": agent_task_repository, "workflow_instance_repository": workflow_instance_repository,
        "reasoning": conversation_reasoning_port, "knowledge": knowledge_retrieval_port,
        "ticket_workflow_client": ticket_workflow_client,
    }


def _command(conversation_id, text="my vpn is broken", idempotency_key="msg-1", requester_subject="employee-1") -> SendMessageCommand:
    return SendMessageCommand(
        conversation_id=conversation_id, requester_subject=requester_subject, text=text, attachment_refs=(),
        idempotency_key=IdempotencyKey(idempotency_key),
    )


def test_a_text_outcome_completes_the_task_and_returns_the_reply(wiring) -> None:
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="text", text="Have you tried turning it off and on again?")

    view = wiring["service"].send_message(_command(wiring["conversation_id"]))

    assert view.kind == "text"
    assert view.text == "Have you tried turning it off and on again?"
    tasks = wiring["agent_task_repository"].find_by_workflow_instance_id(wiring["conversation_id"])
    assert len(tasks) == 1
    assert tasks[0].state is AgentTaskState.COMPLETED
    assert tasks[0].task_type == "process_user_message"


def test_a_proposed_action_outcome_leaves_the_task_awaiting_confirmation(wiring) -> None:
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="proposed_action", action_summary="Reset your password", action_risk_level="LOW")

    view = wiring["service"].send_message(_command(wiring["conversation_id"]))

    assert view.kind == "proposedAction"
    assert view.action_summary == "Reset your password"
    assert view.action_risk_level == "LOW"
    assert view.action_id is not None
    tasks = wiring["agent_task_repository"].find_by_workflow_instance_id(wiring["conversation_id"])
    assert tasks[0].state is AgentTaskState.AWAITING_USER_CONFIRMATION


def test_an_escalation_outcome_triages_the_real_ticket_and_completes_the_workflow(wiring) -> None:
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="escalation", escalation_reason="Needs a technician")

    view = wiring["service"].send_message(_command(wiring["conversation_id"]))

    assert view.kind == "escalation"
    assert view.reason == "Needs a technician"
    assert view.display_id == "INC-2000"
    assert view.assigned_team == "IT Support"
    assert len(wiring["ticket_workflow_client"].triage_calls) == 1

    workflow = wiring["workflow_instance_repository"].find_by_id(wiring["conversation_id"])
    assert workflow.state is WorkflowState.COMPLETED
    assert workflow.ticket_version == 1


def test_escalation_fails_closed_when_routing_is_not_configured(wiring) -> None:
    wiring["service"]._escalation_category_id = ""
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="escalation", escalation_reason="Needs a technician")

    with pytest.raises(EscalationRoutingNotConfiguredException):
        wiring["service"].send_message(_command(wiring["conversation_id"]))


def test_a_repeated_idempotency_key_returns_the_cached_result_without_creating_a_second_task(wiring) -> None:
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="text", text="first reply")

    first = wiring["service"].send_message(_command(wiring["conversation_id"], idempotency_key="msg-dup-1"))
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="text", text="second reply, should never be seen")
    second = wiring["service"].send_message(_command(wiring["conversation_id"], idempotency_key="msg-dup-1"))

    assert first.text == second.text == "first reply"
    assert len(wiring["agent_task_repository"].find_by_workflow_instance_id(wiring["conversation_id"])) == 1


def test_a_reused_idempotency_key_with_different_text_is_rejected(wiring) -> None:
    wiring["service"].send_message(_command(wiring["conversation_id"], text="first text", idempotency_key="msg-conflict-1"))

    with pytest.raises(IdempotencyKeyReusedException):
        wiring["service"].send_message(_command(wiring["conversation_id"], text="different text", idempotency_key="msg-conflict-1"))


def test_an_unknown_conversation_id_is_rejected(wiring) -> None:
    from agentruntime.domain.ids import WorkflowInstanceId

    with pytest.raises(ConversationNotFoundException):
        wiring["service"].send_message(_command(WorkflowInstanceId.new_id()))


def test_a_message_from_a_different_employee_is_denied(wiring) -> None:
    with pytest.raises(ConversationAccessDeniedException):
        wiring["service"].send_message(_command(wiring["conversation_id"], requester_subject="employee-2"))
