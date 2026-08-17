from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import ConsumeTicketClosedCommand, ConsumeTicketResolvedCommand
from memoryknowledge.application.exceptions import PoisonMemorySourceEventException
from memoryknowledge.application.services.consume_ticket_memory_source_event import ConsumeTicketMemorySourceEventService
from memoryknowledge.application.services.extract_memory_candidate import ExtractMemoryCandidateService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.domain.ids import CorrelationId, MemoryCandidateId, TicketCycleId, TicketId
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
    service = ConsumeTicketMemorySourceEventService(processed_event_repository, extract_service, SystemClockAdapter(), InMemoryPoisonEventRepository())
    return service, candidate_repository, outbox_repository, processed_event_repository


def _resolved_command(event_id: str = "evt-1") -> ConsumeTicketResolvedCommand:
    return ConsumeTicketResolvedCommand(
        event_id=event_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        resolution_code="MFA_RESET_SUCCESSFUL", resolution_summary="reset device binding fixed the mfa loop",
        resolved_by="verification-agent", resolved_at=datetime.now(UTC), correlation_id=CorrelationId.new_id(),
    )


def _closed_command(event_id: str = "evt-2") -> ConsumeTicketClosedCommand:
    return ConsumeTicketClosedCommand(
        event_id=event_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        close_reason_code="REQUESTER_CONFIRMED", close_reason="requester confirmed the fix resolved the issue",
        closed_by="employee-1", closed_at=datetime.now(UTC), correlation_id=CorrelationId.new_id(),
    )


def _created_candidate_id(outbox_repository: InMemoryOutboxRepository) -> MemoryCandidateId:
    [record] = [r for r in outbox_repository.recorded() if r.event_type == "memory.candidate.created.v1"]
    return MemoryCandidateId(uuid.UUID(record.aggregate_id))


def test_consume_resolved_extracts_an_episodic_candidate() -> None:
    service, candidate_repository, outbox_repository, _ = _build_service()

    applied = service.consume_resolved(_resolved_command())

    assert applied is True
    candidate = candidate_repository.find_by_id(_created_candidate_id(outbox_repository))
    assert candidate.memory_type.name == "EPISODIC"
    assert candidate.source_refs[0].source_type == "ticket"
    assert "reset device binding fixed the mfa loop" in candidate.candidate_text


def test_consume_resolved_deduplicates_a_replayed_event_id() -> None:
    service, candidate_repository, outbox_repository, _ = _build_service()
    command = _resolved_command("evt-dup")

    first = service.consume_resolved(command)
    second = service.consume_resolved(command)

    assert first is True
    assert second is False
    assert len([r for r in outbox_repository.recorded() if r.event_type == "memory.candidate.created.v1"]) == 1


def test_consume_closed_extracts_its_own_episodic_candidate() -> None:
    service, candidate_repository, outbox_repository, _ = _build_service()

    applied = service.consume_closed(_closed_command())

    assert applied is True
    candidate = candidate_repository.find_by_id(_created_candidate_id(outbox_repository))
    assert "requester confirmed the fix resolved the issue" in candidate.candidate_text


def test_consume_resolved_marks_processed_only_after_a_successful_extraction() -> None:
    """10-failure-handling §"Poison Event": "不标记 processed，除非明确 quarantine" — a
    failing downstream extraction (e.g. an unavailable extraction backend) must leave
    the event retryable, unlike agent-runtime-service's own unconditional `finally:
    mark_processed(...)` precedent.
    """

    class _FailingExtractPort:
        def extract(self, command):
            raise RuntimeError("extraction backend unavailable")

    processed_event_repository = InMemoryProcessedEventRepository()
    service = ConsumeTicketMemorySourceEventService(processed_event_repository, _FailingExtractPort(), SystemClockAdapter(), InMemoryPoisonEventRepository())
    command = _resolved_command("evt-poison")

    with pytest.raises(RuntimeError):
        service.consume_resolved(command)

    assert processed_event_repository.is_processed(command.event_id, "consume_ticket_memory_source_event") is False


def test_a_conflicting_resolution_summary_under_the_same_ticket_cycle_is_recorded_as_a_poison_event() -> None:
    """SPEC-MK-029 10-failure-handling §"Poison Event": this domain's own event router
    already validates payload *shape* at the FastAPI/Pydantic boundary, so the one
    invariant-violation this layer can actually raise is IdempotencyKeyReusedException
    — two ticket.resolved.v1 deliveries for the same ticket_id/ticket_cycle_id (the
    idempotency_key's own scope) carrying genuinely different resolution_summary text.
    """
    poison_event_repository = InMemoryPoisonEventRepository()
    candidate_repository = InMemoryMemoryCandidateRepository()
    extract_service = ExtractMemoryCandidateService(
        candidate_repository, InMemoryCommandIdempotencyRepository(), InMemoryOutboxRepository(), InMemoryAuditRecordRepository(),
        SystemClockAdapter(), MemoryTelemetry(),
    )
    processed_event_repository = InMemoryProcessedEventRepository()
    service = ConsumeTicketMemorySourceEventService(processed_event_repository, extract_service, SystemClockAdapter(), poison_event_repository)

    ticket_id, ticket_cycle_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    first = ConsumeTicketResolvedCommand(
        event_id="evt-first", ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id,
        resolution_code="MFA_RESET_SUCCESSFUL", resolution_summary="reset device binding fixed the mfa loop",
        resolved_by="verification-agent", resolved_at=datetime.now(UTC), correlation_id=CorrelationId.new_id(),
    )
    second = ConsumeTicketResolvedCommand(
        event_id="evt-second", ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id,
        resolution_code="MFA_RESET_SUCCESSFUL", resolution_summary="a completely different, conflicting resolution narrative",
        resolved_by="verification-agent", resolved_at=datetime.now(UTC), correlation_id=CorrelationId.new_id(),
    )

    assert service.consume_resolved(first) is True

    with pytest.raises(PoisonMemorySourceEventException):
        service.consume_resolved(second)

    assert processed_event_repository.is_processed("evt-second", "consume_ticket_memory_source_event") is False
    [poison_record] = poison_event_repository.find_all(limit=10)
    assert poison_record.event_id == "evt-second"
    assert poison_record.consumer_name == "consume_ticket_memory_source_event"
    assert poison_record.event_type == "ticket.resolved.v1"
    assert "conflicting resolution narrative" in poison_record.payload
