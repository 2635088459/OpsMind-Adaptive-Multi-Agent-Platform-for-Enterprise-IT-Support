"""SPEC-ARO-040 (phase-10 Conversational Intake): ActionConfirmationService."""

from __future__ import annotations

import dataclasses
import threading
import time
import uuid

import pytest

from agentruntime.application.commands import (
    ConfirmActionCommand,
    DeclineActionCommand,
    SendMessageCommand,
    StartConversationCommand,
)
from agentruntime.application.exceptions import ConversationAccessDeniedException
from agentruntime.application.records import (
    ApprovalRequestRef,
    CreatedTicketRef,
    KnowledgeSnippet,
    ReasoningOutcome,
)
from agentruntime.application.services.action_confirmation import (
    ActionConfirmationService,
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
from agentruntime.domain.ids import (
    AgentTaskId,
    IdempotencyKey,
    TicketCycleId,
    TicketId,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


class _FakeTicketWorkflowClient:
    def __init__(self) -> None:
        self.ticket_id = TicketId(uuid.uuid4())
        self.ticket_cycle_id = TicketCycleId(uuid.uuid4())

    def create_ticket(self, forwarded_bearer_token: str, idempotency_key: str) -> CreatedTicketRef:
        return CreatedTicketRef(ticket_id=self.ticket_id, ticket_cycle_id=self.ticket_cycle_id, version=0, display_id="INC-4000")


class _FakeKnowledgeRetrievalPort:
    def search(self, query, workflow_instance_id, requester_subject) -> list[KnowledgeSnippet]:
        return []


class _FakeConversationReasoningPort:
    def __init__(self) -> None:
        self.next_outcome = ReasoningOutcome(kind="proposed_action", action_summary="Reset your password", action_risk_level="LOW")

    def decide(self, message_text, knowledge_snippets) -> ReasoningOutcome:
        return self.next_outcome


class _FakeGovernanceApprovalClient:
    def __init__(self) -> None:
        self.calls: list[tuple] = []

    def request_approval(self, agent_task_id, workflow_instance_id, ticket_id, risk_level, reason) -> ApprovalRequestRef:
        self.calls.append((agent_task_id, workflow_instance_id, ticket_id, risk_level, reason))
        return ApprovalRequestRef(approval_request_id=f"approval-{uuid.uuid4()}", status="REQUESTED")


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    tool_request_repository = InMemoryToolRequestRepository()
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
    reasoning_port = _FakeConversationReasoningPort()
    send_message_service = SendMessageService(
        workflow_instance_repository, agent_task_repository, checkpoint_repository, command_idempotency_repository, clock,
        _FakeKnowledgeRetrievalPort(), reasoning_port, ticket_workflow_client, complete_workflow_service,
        escalation_category_id="cat-1", escalation_support_queue_id="queue-1", escalation_priority="MEDIUM",
        escalation_team_name="IT Support",
    )
    governance_approval_client = _FakeGovernanceApprovalClient()
    action_confirmation_service = ActionConfirmationService(
        workflow_instance_repository, agent_task_repository, checkpoint_repository, tool_request_repository,
        command_idempotency_repository, clock, governance_approval_client,
        bounded_wait_timeout_seconds=0.05, bounded_wait_poll_interval_seconds=0.01,
    )

    conversation = start_conversation_service.start_conversation(StartConversationCommand(
        requester_subject="employee-1", forwarded_bearer_token="jwt", idempotency_key=IdempotencyKey("conv-1"),
    ))
    return {
        "action_service": action_confirmation_service, "send_message_service": send_message_service,
        "conversation_id": conversation.conversation_id, "agent_task_repository": agent_task_repository,
        "tool_request_repository": tool_request_repository, "workflow_instance_repository": workflow_instance_repository,
        "reasoning": reasoning_port, "governance_client": governance_approval_client, "clock": clock,
    }


def _propose_action(wiring, requester_subject: str = "employee-1", message_idempotency_key: str = "msg-1") -> AgentTaskId:
    view = wiring["send_message_service"].send_message(SendMessageCommand(
        conversation_id=wiring["conversation_id"], requester_subject=requester_subject, text="I need to reset my password",
        attachment_refs=(), idempotency_key=IdempotencyKey(message_idempotency_key),
    ))
    assert view.kind == "proposedAction"
    return AgentTaskId(uuid.UUID(view.action_id))


def _confirm_command(wiring, action_id: AgentTaskId, idempotency_key: str = "confirm-1", requester_subject: str = "employee-1") -> ConfirmActionCommand:
    return ConfirmActionCommand(
        conversation_id=wiring["conversation_id"], action_id=action_id, requester_subject=requester_subject,
        idempotency_key=IdempotencyKey(idempotency_key),
    )


def _decline_command(wiring, action_id: AgentTaskId, idempotency_key: str = "decline-1", requester_subject: str = "employee-1") -> DeclineActionCommand:
    return DeclineActionCommand(
        conversation_id=wiring["conversation_id"], action_id=action_id, requester_subject=requester_subject,
        idempotency_key=IdempotencyKey(idempotency_key),
    )


def test_confirming_a_low_risk_action_dispatches_a_real_tool_request(wiring) -> None:
    action_id = _propose_action(wiring)

    view = wiring["action_service"].confirm_action(_confirm_command(wiring, action_id))

    assert view.outcome == "still-processing"  # nothing ever completes it in this test's own bounded window
    tool_requests = wiring["tool_request_repository"].find_pending(10)
    assert len(tool_requests) == 1
    assert tool_requests[0].agent_task_id == action_id

    task = wiring["agent_task_repository"].find_by_id(action_id)
    assert task.state is AgentTaskState.WAITING_TOOL
    workflow = wiring["workflow_instance_repository"].find_by_id(wiring["conversation_id"])
    assert workflow.state is WorkflowState.WAITING_FOR_TOOL


def test_confirming_a_low_risk_action_resolves_done_if_the_tool_completes_within_the_bound(wiring) -> None:
    action_id = _propose_action(wiring)

    def complete_shortly_after() -> None:
        time.sleep(0.02)
        task = wiring["agent_task_repository"].find_by_id(action_id)
        # Simulates SPEC-ARO-020's own tool.completed.v1 consumer completing the task —
        # not re-implemented here, this test only proves the *waiting* side works.
        wiring["agent_task_repository"].save(dataclasses.replace(
            task, state=AgentTaskState.COMPLETED, task_version=task.task_version + 1, result_payload='{"kind":"done"}',
        ))

    threading.Thread(target=complete_shortly_after).start()

    view = wiring["action_service"].confirm_action(_confirm_command(wiring, action_id))

    assert view.outcome == "done"


def test_confirming_a_high_risk_action_creates_a_real_governance_approval_request(wiring) -> None:
    wiring["reasoning"].next_outcome = ReasoningOutcome(kind="proposed_action", action_summary="Delete all logs", action_risk_level="HIGH")
    action_id = _propose_action(wiring, message_idempotency_key="msg-high-1")

    view = wiring["action_service"].confirm_action(_confirm_command(wiring, action_id, idempotency_key="confirm-high-1"))

    assert view.outcome == "awaiting-approval"
    assert len(wiring["governance_client"].calls) == 1
    _, _, _, risk_level, _ = wiring["governance_client"].calls[0]
    assert risk_level == "HIGH"

    task = wiring["agent_task_repository"].find_by_id(action_id)
    assert task.state is AgentTaskState.WAITING_EXTERNAL
    workflow = wiring["workflow_instance_repository"].find_by_id(wiring["conversation_id"])
    assert workflow.state is WorkflowState.WAITING_FOR_APPROVAL
    # domain-rules: never attempts the bounded wait at all for this branch.
    assert len(wiring["tool_request_repository"].find_pending(10)) == 0


def test_declining_an_action_has_zero_side_effects(wiring) -> None:
    action_id = _propose_action(wiring, message_idempotency_key="msg-decline-1")

    view = wiring["action_service"].decline_action(_decline_command(wiring, action_id))

    assert view.outcome == "declined"
    assert wiring["tool_request_repository"].find_pending(10) == []
    assert wiring["governance_client"].calls == []
    task = wiring["agent_task_repository"].find_by_id(action_id)
    assert task.state is AgentTaskState.COMPLETED
    workflow = wiring["workflow_instance_repository"].find_by_id(wiring["conversation_id"])
    assert workflow.state is WorkflowState.RUNNING  # declining never touches the workflow's own state


def test_confirming_an_already_declined_action_honestly_returns_declined(wiring) -> None:
    action_id = _propose_action(wiring, message_idempotency_key="msg-repeat-1")
    wiring["action_service"].decline_action(_decline_command(wiring, action_id, idempotency_key="decline-first"))

    view = wiring["action_service"].confirm_action(_confirm_command(wiring, action_id, idempotency_key="confirm-after-decline"))

    assert view.outcome == "declined"
    # No new side effect from this second, "too-late" confirm attempt.
    assert wiring["tool_request_repository"].find_pending(10) == []


def test_a_repeated_confirm_idempotency_key_never_dispatches_a_second_tool_request(wiring) -> None:
    action_id = _propose_action(wiring, message_idempotency_key="msg-idem-1")

    wiring["action_service"].confirm_action(_confirm_command(wiring, action_id, idempotency_key="confirm-idem-1"))
    wiring["action_service"].confirm_action(_confirm_command(wiring, action_id, idempotency_key="confirm-idem-1"))

    assert len(wiring["tool_request_repository"].find_pending(10)) == 1


def test_confirm_denies_a_different_employee(wiring) -> None:
    action_id = _propose_action(wiring, message_idempotency_key="msg-deny-1")

    with pytest.raises(ConversationAccessDeniedException):
        wiring["action_service"].confirm_action(_confirm_command(wiring, action_id, requester_subject="employee-2"))
