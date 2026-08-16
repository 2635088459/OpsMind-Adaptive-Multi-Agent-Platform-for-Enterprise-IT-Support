from __future__ import annotations

import pytest

from memoryknowledge.application.commands import ExtractMemoryCandidateCommand
from memoryknowledge.application.services.extract_memory_candidate import ExtractMemoryCandidateService
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import IdempotencyKey
from memoryknowledge.domain.values import SourceRef
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryCommandIdempotencyRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryOutboxRepository,
)

pytestmark = pytest.mark.unit


def _build_service():
    candidate_repository = InMemoryMemoryCandidateRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = ExtractMemoryCandidateService(candidate_repository, InMemoryCommandIdempotencyRepository(), outbox_repository, SystemClockAdapter())
    return service, candidate_repository, outbox_repository


def _command(idempotency_key: str = "extract-1") -> ExtractMemoryCandidateCommand:
    return ExtractMemoryCandidateCommand(
        memory_type=MemoryType.EPISODIC, source_refs=(SourceRef("ticket", "T-1"),), candidate_text="vpn fails after mfa reset",
        idempotency_key=IdempotencyKey(idempotency_key), extracted_by="agent-1",
    )


def test_extract_creates_a_candidate_and_publishes_created_event() -> None:
    service, candidate_repository, outbox_repository = _build_service()

    view = service.extract(_command())

    assert view.status.name == "EXTRACTED"
    assert candidate_repository.find_by_id(view.candidate_id) is not None
    assert any(r.event_type == "memory.candidate.created.v1" for r in outbox_repository.recorded())


def test_duplicate_delivery_under_the_same_idempotency_key_does_not_create_a_second_candidate() -> None:
    service, candidate_repository, outbox_repository = _build_service()

    first = service.extract(_command("extract-dup"))
    second = service.extract(_command("extract-dup"))

    assert first.candidate_id == second.candidate_id
    assert len([r for r in outbox_repository.recorded() if r.event_type == "memory.candidate.created.v1"]) == 1


def test_a_different_idempotency_key_creates_a_new_candidate() -> None:
    service, _, _ = _build_service()

    first = service.extract(_command("extract-a"))
    second = service.extract(_command("extract-b"))

    assert first.candidate_id != second.candidate_id
