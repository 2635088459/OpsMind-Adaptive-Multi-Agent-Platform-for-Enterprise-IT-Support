from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import ConsumeWorkflowCompletedCommand, ConsumeWorkflowFailedCommand
from memoryknowledge.application.exceptions import PoisonMemorySourceEventException
from memoryknowledge.application.services.consume_workflow_memory_source_event import ConsumeWorkflowMemorySourceEventService
from memoryknowledge.application.services.extract_memory_candidate import ExtractMemoryCandidateService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.domain.ids import CorrelationId, MemoryCandidateId, TicketId, WorkflowInstanceId
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryOutboxRepository,
    InMemoryPoisonEventRepository,
    InMemoryProcessedEventRepository,
)

pytestmark = pytest.mark.unit


def _build_service():
    candidate_repository = InMemoryMemoryCandidateRepository()
    outbox_repository = InMemoryOutboxRepository()
    extract_service = ExtractMemoryCandidateService(
        candidate_repository, InMemoryCommandIdempotencyRepository(), outbox_repository, InMemoryAuditRecordRepository(),
        SystemClockAdapter(),
        MemoryTelemetry(),
    )
    processed_event_repository = InMemoryProcessedEventRepository()
    service = ConsumeWorkflowMemorySourceEventService(processed_event_repository, extract_service, SystemClockAdapter(), InMemoryPoisonEventRepository())
    return service, candidate_repository, outbox_repository, processed_event_repository


def _completed_command(event_id: str = "evt-wf-1") -> ConsumeWorkflowCompletedCommand:
    return ConsumeWorkflowCompletedCommand(
        event_id=event_id, workflow_instance_id=WorkflowInstanceId(uuid.uuid4()), ticket_id=TicketId(uuid.uuid4()),
        from_state="IN_PROGRESS", to_state="COMPLETED", workflow_version=3, occurred_at=datetime.now(UTC),
        correlation_id=CorrelationId.new_id(),
    )


def _failed_command(event_id: str = "evt-wf-2") -> ConsumeWorkflowFailedCommand:
    return ConsumeWorkflowFailedCommand(
        event_id=event_id, workflow_instance_id=WorkflowInstanceId(uuid.uuid4()), ticket_id=TicketId(uuid.uuid4()),
        from_state="IN_PROGRESS", to_state="FAILED", workflow_version=2, failure_reason="tool gateway timeout",
        occurred_at=datetime.now(UTC), correlation_id=CorrelationId.new_id(),
    )


def _created_candidate_id(outbox_repository: InMemoryOutboxRepository) -> MemoryCandidateId:
    [record] = [r for r in outbox_repository.recorded() if r.event_type == "memory.candidate.created.v1"]
    return MemoryCandidateId(uuid.UUID(record.aggregate_id))


def test_consume_completed_extracts_an_episodic_candidate() -> None:
    service, candidate_repository, outbox_repository, _ = _build_service()

    applied = service.consume_completed(_completed_command())

    assert applied is True
    candidate = candidate_repository.find_by_id(_created_candidate_id(outbox_repository))
    assert candidate.memory_type.name == "EPISODIC"
    assert candidate.source_refs[0].source_type == "workflow"
    assert "IN_PROGRESS -> COMPLETED" in candidate.candidate_text


def test_consume_completed_deduplicates_a_replayed_event_id() -> None:
    service, candidate_repository, outbox_repository, _ = _build_service()
    command = _completed_command("evt-wf-dup")

    first = service.consume_completed(command)
    second = service.consume_completed(command)

    assert first is True
    assert second is False
    assert len([r for r in outbox_repository.recorded() if r.event_type == "memory.candidate.created.v1"]) == 1


def test_consume_failed_extracts_its_own_episodic_candidate_carrying_the_failure_reason() -> None:
    """06-event-contracts: "workflow.failed.v1 用途：记录失败经验，但默认不自动发布为
    procedural memory" — starting from EPISODIC (never PROCEDURAL) satisfies the
    "not procedural" half; nothing in this codebase auto-approves any extracted
    candidate, so the "不自动发布" half is already structurally guaranteed.
    """
    service, candidate_repository, outbox_repository, _ = _build_service()

    applied = service.consume_failed(_failed_command())

    assert applied is True
    candidate = candidate_repository.find_by_id(_created_candidate_id(outbox_repository))
    assert candidate.memory_type.name == "EPISODIC"
    assert candidate.source_refs[0].source_type == "workflow"
    assert "tool gateway timeout" in candidate.candidate_text


def test_consume_completed_marks_processed_only_after_a_successful_extraction() -> None:
    """10-failure-handling §"Poison Event": "不标记 processed，除非明确 quarantine" —
    mirrors SPEC-MK-010's own consumer's divergence from agent-runtime-service's own
    unconditional `finally: mark_processed(...)` precedent.
    """

    class _FailingExtractPort:
        def extract(self, command):
            raise RuntimeError("extraction backend unavailable")

    processed_event_repository = InMemoryProcessedEventRepository()
    service = ConsumeWorkflowMemorySourceEventService(processed_event_repository, _FailingExtractPort(), SystemClockAdapter(), InMemoryPoisonEventRepository())
    command = _completed_command("evt-wf-poison")

    with pytest.raises(RuntimeError):
        service.consume_completed(command)

    assert processed_event_repository.is_processed(command.event_id, "consume_workflow_memory_source_event") is False


def test_conflicting_workflow_versions_under_the_same_instance_are_recorded_as_a_poison_event() -> None:
    """SPEC-MK-029 10-failure-handling §"Poison Event" — same IdempotencyKeyReusedException
    poison-detection shape as ConsumeTicketMemorySourceEventService's own; here the
    idempotency_key scope is workflow_instance_id + workflow_version.
    """
    poison_event_repository = InMemoryPoisonEventRepository()
    candidate_repository = InMemoryMemoryCandidateRepository()
    extract_service = ExtractMemoryCandidateService(
        candidate_repository, InMemoryCommandIdempotencyRepository(), InMemoryOutboxRepository(), InMemoryAuditRecordRepository(),
        SystemClockAdapter(), MemoryTelemetry(),
    )
    processed_event_repository = InMemoryProcessedEventRepository()
    service = ConsumeWorkflowMemorySourceEventService(processed_event_repository, extract_service, SystemClockAdapter(), poison_event_repository)

    workflow_instance_id, ticket_id = WorkflowInstanceId(uuid.uuid4()), TicketId(uuid.uuid4())
    first = ConsumeWorkflowCompletedCommand(
        event_id="evt-wf-first", workflow_instance_id=workflow_instance_id, ticket_id=ticket_id,
        from_state="IN_PROGRESS", to_state="COMPLETED", workflow_version=5, occurred_at=datetime.now(UTC),
        correlation_id=CorrelationId.new_id(),
    )
    second = ConsumeWorkflowCompletedCommand(
        event_id="evt-wf-second", workflow_instance_id=workflow_instance_id, ticket_id=ticket_id,
        from_state="BLOCKED", to_state="COMPLETED", workflow_version=5, occurred_at=datetime.now(UTC),
        correlation_id=CorrelationId.new_id(),
    )

    assert service.consume_completed(first) is True

    with pytest.raises(PoisonMemorySourceEventException):
        service.consume_completed(second)

    assert processed_event_repository.is_processed("evt-wf-second", "consume_workflow_memory_source_event") is False
    [poison_record] = poison_event_repository.find_all(limit=10)
    assert poison_record.event_id == "evt-wf-second"
    assert poison_record.event_type == "workflow.completed.v1"
