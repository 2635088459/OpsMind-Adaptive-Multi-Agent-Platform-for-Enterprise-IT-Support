from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from agentruntime.domain import workflow_instance
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException, InvalidWorkflowTransitionException
from agentruntime.domain.ids import DefinitionVersion, IdempotencyKey, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)
WORKFLOW_INSTANCE_ID = WorkflowInstanceId.new_id()


def test_start_produces_running_state_at_version_one() -> None:
    event = workflow_instance.start(
        WORKFLOW_INSTANCE_ID, TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()),
        WorkflowType("TICKET_TRIAGE"), WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), NOW,
    )

    assert event.to_state is WorkflowState.RUNNING
    assert event.workflow_version == 1
    assert event.from_state is None


def test_pause_from_running_increments_version_and_pause_generation() -> None:
    event = workflow_instance.pause(WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, 1, 0, IdempotencyKey("pause-key-1"), NOW)

    assert event.to_state is WorkflowState.PAUSED
    assert event.workflow_version == 2
    assert event.pause_generation == 1


def test_pause_from_already_paused_is_rejected_by_the_domain_function() -> None:
    # The Application layer is responsible for idempotent dedup before ever calling this
    # function; this asserts the pure domain guard that backs that contract.
    with pytest.raises(InvalidWorkflowStateException):
        workflow_instance.pause(WORKFLOW_INSTANCE_ID, WorkflowState.PAUSED, 2, 1, IdempotencyKey("pause-key-2"), NOW)


def test_resume_from_paused_restores_running_without_changing_pause_generation() -> None:
    event = workflow_instance.resume(WORKFLOW_INSTANCE_ID, WorkflowState.PAUSED, 2, 1, IdempotencyKey("resume-key-1"), NOW)

    assert event.to_state is WorkflowState.RUNNING
    assert event.workflow_version == 3
    assert event.pause_generation == 1


def test_resume_from_running_is_rejected() -> None:
    with pytest.raises(InvalidWorkflowTransitionException):
        workflow_instance.resume(WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, 1, 0, IdempotencyKey("resume-key-2"), NOW)


def test_complete_from_running_reaches_terminal_state() -> None:
    event = workflow_instance.complete(WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, 1, NOW)

    assert event.to_state is WorkflowState.COMPLETED
    assert event.workflow_version == 2


def test_complete_from_terminal_state_is_rejected() -> None:
    with pytest.raises(InvalidWorkflowStateException):
        workflow_instance.complete(WORKFLOW_INSTANCE_ID, WorkflowState.COMPLETED, 3, NOW)


def test_fail_requires_a_non_blank_reason() -> None:
    with pytest.raises(ValueError):
        workflow_instance.fail(WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, 1, "   ", NOW)

    event = workflow_instance.fail(WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, 1, "tool exhausted retries", NOW)
    assert event.to_state is WorkflowState.FAILED
    assert event.failure_reason == "tool exhausted retries"


def test_cancel_from_non_terminal_state_succeeds() -> None:
    event = workflow_instance.cancel(WORKFLOW_INSTANCE_ID, WorkflowState.PAUSED, 2, "ticket cancelled upstream", NOW)

    assert event.to_state is WorkflowState.CANCELLED
    assert event.workflow_version == 3


@pytest.mark.parametrize("waiting_state", [
    WorkflowState.WAITING_FOR_APPROVAL, WorkflowState.WAITING_FOR_TOOL,
    WorkflowState.WAITING_FOR_VERIFICATION, WorkflowState.WAITING_FOR_INPUT,
])
def test_pause_from_a_waiting_state_succeeds(waiting_state: WorkflowState) -> None:
    """03-state-machine: pause must be able to freeze a workflow currently blocked on an
    external wake-up, not only one actively RUNNING (SPEC-ARO-004).
    """
    event = workflow_instance.pause(WORKFLOW_INSTANCE_ID, waiting_state, 1, 0, IdempotencyKey("pause-key-3"), NOW)

    assert event.to_state is WorkflowState.PAUSED


@pytest.mark.parametrize("waiting_state", [
    WorkflowState.WAITING_FOR_APPROVAL, WorkflowState.WAITING_FOR_TOOL,
    WorkflowState.WAITING_FOR_VERIFICATION, WorkflowState.WAITING_FOR_INPUT,
])
def test_a_waiting_state_is_neither_terminal_nor_missing_from_is_waiting(waiting_state: WorkflowState) -> None:
    assert waiting_state.is_terminal() is False
    assert waiting_state.is_waiting() is True


@pytest.mark.parametrize("state", [
    WorkflowState.CREATED, WorkflowState.STARTING, WorkflowState.RUNNING, WorkflowState.PAUSING,
    WorkflowState.PAUSED, WorkflowState.RESUMING, WorkflowState.COMPLETED, WorkflowState.FAILED, WorkflowState.CANCELLED,
])
def test_a_non_waiting_state_reports_is_waiting_false(state: WorkflowState) -> None:
    assert state.is_waiting() is False
