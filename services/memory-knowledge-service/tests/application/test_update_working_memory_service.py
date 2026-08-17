from __future__ import annotations

import uuid

import pytest

from memoryknowledge.application.commands import (
    ArchiveWorkingMemoryCommand,
    DeleteWorkingMemoryCommand,
    QueryWorkingMemoryCommand,
    RejectHypothesisInput,
    ToolEvidenceRefInput,
    UpdateWorkingMemoryCommand,
)
from memoryknowledge.application.exceptions import WorkingMemoryNotFoundException
from memoryknowledge.application.services.update_working_memory import UpdateWorkingMemoryService
from memoryknowledge.domain.exceptions import InvalidWorkingMemoryStateException, WorkingMemoryVersionConflictException
from memoryknowledge.domain.ids import CorrelationId, TicketCycleId, TicketId, WorkflowInstanceId, WorkingMemoryId
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import InMemoryAuditRecordRepository, InMemoryWorkingMemoryRepository
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter

pytestmark = pytest.mark.unit


def _scope() -> tuple[TicketId, TicketCycleId, WorkflowInstanceId]:
    return TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()), WorkflowInstanceId(uuid.uuid4())


def _service() -> UpdateWorkingMemoryService:
    return UpdateWorkingMemoryService(
        InMemoryWorkingMemoryRepository(), RegexRedactionPolicyAdapter(), InMemoryAuditRecordRepository(), SystemClockAdapter(),
    )


def _command(ticket_id: TicketId, cycle_id: TicketCycleId, workflow_id: WorkflowInstanceId, **overrides) -> UpdateWorkingMemoryCommand:
    defaults = dict(
        ticket_id=ticket_id, ticket_cycle_id=cycle_id, workflow_instance_id=workflow_id,
        expected_version=0, updated_by="agent-1", correlation_id=CorrelationId.new_id(),
    )
    defaults.update(overrides)
    return UpdateWorkingMemoryCommand(**defaults)


def test_first_write_creates_a_working_memory_at_expected_version_zero() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()

    view = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, add_facts=("vpn down",)))

    assert view.facts == ("vpn down",)
    assert view.status.name == "ACTIVE"


def test_second_write_requires_the_current_version() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    first = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))

    second = service.update_working_memory(
        _command(ticket_id, cycle_id, workflow_id, expected_version=first.version, add_facts=("mfa reset needed",))
    )
    assert second.facts == ("mfa reset needed",)

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.update_working_memory(
            _command(ticket_id, cycle_id, workflow_id, expected_version=first.version, add_facts=("stale write",))
        )


def test_reject_hypothesis_is_persisted_with_reason() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, add_hypotheses=("bad cable",)))

    updated = service.update_working_memory(_command(
        ticket_id, cycle_id, workflow_id, expected_version=created.version,
        reject_hypotheses=(RejectHypothesisInput("bad cable", "cable tested fine"),),
    ))

    assert updated.hypotheses == ()
    assert updated.rejected_hypotheses[0].reason == "cable tested fine"


def test_a_second_create_attempt_against_an_already_active_scope_conflicts() -> None:
    """01-domain-model: "同一个 scope 只能有一个 active WorkingMemory" — a second caller
    that still believes expected_version=0 (i.e. "nothing exists yet") loses the race
    once the first create has already landed.
    """
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, updated_by="agent-2"))


def test_secrets_pasted_into_facts_hypotheses_context_summary_and_tool_evidence_are_redacted() -> None:
    """SPEC-MK-004 01-domain-model §"WorkingMemory" 约束: "raw secret、完整凭据、未脱敏工具
    输出不能进入正文."
    """
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()

    updated = service.update_working_memory(_command(
        ticket_id, cycle_id, workflow_id,
        add_facts=("contact user@example.com for follow-up",), add_hypotheses=("api_key: abcd1234efgh5678 may be leaked",),
        context_summary="reporter email is user@example.com",
        add_tool_evidence_refs=(ToolEvidenceRefInput("tool-1", "response included token: super-secret-value", "COMPLETED", "hash-1"),),
    ))

    assert "user@example.com" not in " ".join(updated.facts)
    assert "abcd1234efgh5678" not in " ".join(updated.hypotheses)
    assert "user@example.com" not in updated.context_summary
    assert "super-secret-value" not in updated.tool_evidence_refs[0].summary
    assert "***REDACTED***" in " ".join(updated.facts)


def test_update_writes_an_audit_record() -> None:
    """SPEC-MK-005 04-use-cases UC-01 step 7: "写 audit log."."""
    working_memory_repository = InMemoryWorkingMemoryRepository()
    audit_record_repository = InMemoryAuditRecordRepository()
    service = UpdateWorkingMemoryService(working_memory_repository, RegexRedactionPolicyAdapter(), audit_record_repository, SystemClockAdapter())
    ticket_id, cycle_id, workflow_id = _scope()
    correlation_id = CorrelationId.new_id()

    view = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, correlation_id=correlation_id, add_facts=("vpn down",)))

    [entry] = audit_record_repository.find_recent(limit=10)
    assert entry.action == "update_working_memory"
    assert entry.resource_id == str(view.working_memory_id)
    assert entry.actor_id == "agent-1"
    assert entry.correlation_id == str(correlation_id)


def test_find_working_memory_returns_the_current_view() -> None:
    """SPEC-MK-006 05-api-contracts: `GET /internal/memory/v1/working-memory/{workingMemoryId}`."""
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, add_facts=("vpn down",)))

    found = service.find_working_memory(QueryWorkingMemoryCommand(created.working_memory_id, CorrelationId.new_id()))

    assert found.working_memory_id == created.working_memory_id
    assert found.facts == ("vpn down",)


def test_find_working_memory_raises_when_missing() -> None:
    service = _service()
    with pytest.raises(WorkingMemoryNotFoundException):
        service.find_working_memory(QueryWorkingMemoryCommand(WorkingMemoryId(uuid.uuid4()), CorrelationId.new_id()))


def test_archive_bumps_version_and_writes_an_audit_record() -> None:
    """SPEC-MK-006 03-state-machine: "ticket cycle 结束后可 ARCHIVED."."""
    working_memory_repository = InMemoryWorkingMemoryRepository()
    audit_record_repository = InMemoryAuditRecordRepository()
    service = UpdateWorkingMemoryService(working_memory_repository, RegexRedactionPolicyAdapter(), audit_record_repository, SystemClockAdapter())
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))
    correlation_id = CorrelationId.new_id()

    archived = service.archive(ArchiveWorkingMemoryCommand(created.working_memory_id, created.version, "admin-1", correlation_id))

    assert archived.status.name == "ARCHIVED"
    assert archived.version == created.version + 1
    entries = [e for e in audit_record_repository.find_recent(limit=10) if e.action == "archive_working_memory"]
    assert len(entries) == 1
    assert entries[0].actor_id == "admin-1"
    assert entries[0].correlation_id == str(correlation_id)


def test_archive_rejects_stale_expected_version() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.archive(ArchiveWorkingMemoryCommand(created.working_memory_id, created.version + 1, "admin-1", CorrelationId.new_id()))


def test_archive_raises_when_missing() -> None:
    service = _service()
    with pytest.raises(WorkingMemoryNotFoundException):
        service.archive(ArchiveWorkingMemoryCommand(WorkingMemoryId(uuid.uuid4()), 0, "admin-1", CorrelationId.new_id()))


def test_update_after_archive_is_rejected_through_the_service() -> None:
    """update_working_memory() looks the aggregate up via find_active_by_scope(), which
    an ARCHIVED row no longer satisfies — so the service sees "no active row" rather
    than reaching WorkingMemory.apply_update()'s own InvalidWorkingMemoryStateException
    guard (that guard is exercised directly at the domain layer instead, see
    tests/domain/test_working_memory.py::test_update_after_archive_is_rejected).
    """
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))
    archived = service.archive(ArchiveWorkingMemoryCommand(created.working_memory_id, created.version, "admin-1", CorrelationId.new_id()))

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, expected_version=archived.version, add_facts=("late write",)))


def test_delete_clears_content_and_keeps_the_tombstone_identity() -> None:
    """SPEC-MK-006 03-state-machine: "deletion request 可把 body 清空并保留 tombstone."."""
    working_memory_repository = InMemoryWorkingMemoryRepository()
    audit_record_repository = InMemoryAuditRecordRepository()
    service = UpdateWorkingMemoryService(working_memory_repository, RegexRedactionPolicyAdapter(), audit_record_repository, SystemClockAdapter())
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id, add_facts=("vpn down",)))
    correlation_id = CorrelationId.new_id()

    deleted = service.delete(DeleteWorkingMemoryCommand(created.working_memory_id, created.version, "admin-1", correlation_id))

    assert deleted.status.name == "DELETED"
    assert deleted.facts == ()
    assert deleted.working_memory_id == created.working_memory_id
    entries = [e for e in audit_record_repository.find_recent(limit=10) if e.action == "delete_working_memory"]
    assert len(entries) == 1
    assert entries[0].correlation_id == str(correlation_id)


def test_delete_is_allowed_from_archived_but_rejects_a_second_delete() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))
    archived = service.archive(ArchiveWorkingMemoryCommand(created.working_memory_id, created.version, "admin-1", CorrelationId.new_id()))

    deleted = service.delete(DeleteWorkingMemoryCommand(archived.working_memory_id, archived.version, "admin-1", CorrelationId.new_id()))
    assert deleted.status.name == "DELETED"

    with pytest.raises(InvalidWorkingMemoryStateException):
        service.delete(DeleteWorkingMemoryCommand(deleted.working_memory_id, deleted.version, "admin-1", CorrelationId.new_id()))


def test_delete_rejects_stale_expected_version() -> None:
    service = _service()
    ticket_id, cycle_id, workflow_id = _scope()
    created = service.update_working_memory(_command(ticket_id, cycle_id, workflow_id))

    with pytest.raises(WorkingMemoryVersionConflictException):
        service.delete(DeleteWorkingMemoryCommand(created.working_memory_id, created.version + 1, "admin-1", CorrelationId.new_id()))


def test_delete_raises_when_missing() -> None:
    service = _service()
    with pytest.raises(WorkingMemoryNotFoundException):
        service.delete(DeleteWorkingMemoryCommand(WorkingMemoryId(uuid.uuid4()), 0, "admin-1", CorrelationId.new_id()))
