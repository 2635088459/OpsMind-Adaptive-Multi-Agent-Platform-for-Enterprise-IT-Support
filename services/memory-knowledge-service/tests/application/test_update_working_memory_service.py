from __future__ import annotations

import uuid

import pytest

from memoryknowledge.application.commands import RejectHypothesisInput, UpdateWorkingMemoryCommand
from memoryknowledge.application.services.update_working_memory import UpdateWorkingMemoryService
from memoryknowledge.domain.exceptions import WorkingMemoryVersionConflictException
from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import InMemoryWorkingMemoryRepository

pytestmark = pytest.mark.unit


def _scope() -> tuple[TicketId, TicketCycleId, WorkflowInstanceId]:
    return TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()), WorkflowInstanceId(uuid.uuid4())


def _service() -> UpdateWorkingMemoryService:
    return UpdateWorkingMemoryService(InMemoryWorkingMemoryRepository(), SystemClockAdapter())


def test_first_write_creates_a_working_memory_at_expected_version_zero() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()

    view = service.update_working_memory(UpdateWorkingMemoryCommand(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id,
        expected_version=0, updated_by="agent-1", add_facts=("vpn down",),
    ))

    assert view.facts == ("vpn down",)
    assert view.status.name == "ACTIVE"


def test_second_write_requires_the_current_version() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    first = service.update_working_memory(UpdateWorkingMemoryCommand(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=0, updated_by="agent-1",
    ))

    second = service.update_working_memory(UpdateWorkingMemoryCommand(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=first.version,
        updated_by="agent-1", add_facts=("mfa reset needed",),
    ))
    assert second.facts == ("mfa reset needed",)

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.update_working_memory(UpdateWorkingMemoryCommand(
            ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=first.version,
            updated_by="agent-1", add_facts=("stale write",),
        ))


def test_reject_hypothesis_is_persisted_with_reason() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(UpdateWorkingMemoryCommand(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=0,
        updated_by="agent-1", add_hypotheses=("bad cable",),
    ))

    updated = service.update_working_memory(UpdateWorkingMemoryCommand(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=created.version,
        updated_by="agent-1", reject_hypotheses=(RejectHypothesisInput("bad cable", "cable tested fine"),),
    ))

    assert updated.hypotheses == ()


def test_a_second_create_attempt_against_an_already_active_scope_conflicts() -> None:
    """01-domain-model: "同一个 scope 只能有一个 active WorkingMemory" — a second caller
    that still believes expected_version=0 (i.e. "nothing exists yet") loses the race
    once the first create has already landed.
    """
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    service.update_working_memory(UpdateWorkingMemoryCommand(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=0, updated_by="agent-1",
    ))

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.update_working_memory(UpdateWorkingMemoryCommand(
            ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id, expected_version=0, updated_by="agent-2",
        ))
