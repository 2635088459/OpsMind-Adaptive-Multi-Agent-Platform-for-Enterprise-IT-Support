from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.domain.exceptions import InvalidWorkingMemoryStateException, WorkingMemoryVersionConflictException
from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId, WorkingMemoryId
from memoryknowledge.domain.working_memory import RejectedHypothesis, WorkingMemory, derive_working_memory_id

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _new_working_memory() -> WorkingMemory:
    return WorkingMemory.create(
        WorkingMemoryId.new_id(), TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()), WorkflowInstanceId(uuid.uuid4()),
        "agent-1", _now(),
    )


def test_create_starts_active_at_version_one() -> None:
    working_memory = _new_working_memory()

    assert working_memory.version == 1
    assert working_memory.status.name == "ACTIVE"
    assert working_memory.facts == ()


def test_apply_update_accumulates_facts_and_bumps_version() -> None:
    working_memory = _new_working_memory()

    updated = working_memory.apply_update(
        expected_version=1, updated_by="agent-1", updated_at=_now(), add_facts=("vpn down",),
    )

    assert updated.version == 2
    assert updated.facts == ("vpn down",)

    updated_again = updated.apply_update(expected_version=2, updated_by="agent-1", updated_at=_now(), add_facts=("mfa reset",))
    assert updated_again.facts == ("vpn down", "mfa reset")


def test_apply_update_with_stale_expected_version_raises() -> None:
    working_memory = _new_working_memory()

    with pytest.raises(WorkingMemoryVersionConflictException):
        working_memory.apply_update(expected_version=99, updated_by="agent-1", updated_at=_now())


def test_reject_hypothesis_keeps_reason_and_removes_from_active_hypotheses() -> None:
    working_memory = _new_working_memory().apply_update(
        expected_version=1, updated_by="agent-1", updated_at=_now(), add_hypotheses=("bad cable",),
    )

    rejected = working_memory.apply_update(
        expected_version=2, updated_by="agent-1", updated_at=_now(),
        reject_hypotheses=(RejectedHypothesis("bad cable", "cable tested fine", _now()),),
    )

    assert rejected.hypotheses == ()
    assert len(rejected.rejected_hypotheses) == 1
    assert rejected.rejected_hypotheses[0].reason == "cable tested fine"


def test_update_after_archive_is_rejected() -> None:
    working_memory = _new_working_memory()
    archived = working_memory.archive(_now())

    assert archived.status.name == "ARCHIVED"
    with pytest.raises(InvalidWorkingMemoryStateException):
        archived.apply_update(expected_version=1, updated_by="agent-1", updated_at=_now())


def test_delete_clears_content_but_keeps_tombstone_identity() -> None:
    working_memory = _new_working_memory().apply_update(
        expected_version=1, updated_by="agent-1", updated_at=_now(), add_facts=("secret leaked",),
    )

    deleted = working_memory.delete(_now())

    assert deleted.status.name == "DELETED"
    assert deleted.facts == ()
    assert deleted.working_memory_id == working_memory.working_memory_id


def test_derive_working_memory_id_is_deterministic_per_scope() -> None:
    ticket_id, ticket_cycle_id, workflow_instance_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()), WorkflowInstanceId(uuid.uuid4())

    first = derive_working_memory_id(ticket_id, ticket_cycle_id, workflow_instance_id)
    second = derive_working_memory_id(ticket_id, ticket_cycle_id, workflow_instance_id)

    assert first == second
    assert derive_working_memory_id(TicketId(uuid.uuid4()), ticket_cycle_id, workflow_instance_id) != first
