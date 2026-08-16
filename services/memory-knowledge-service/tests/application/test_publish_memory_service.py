from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import PublishMemoryCommand
from memoryknowledge.application.exceptions import MemoryCandidateNotFoundException
from memoryknowledge.application.services.publish_memory import PublishMemoryService
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.exceptions import InvalidMemoryCandidateTransitionException
from memoryknowledge.domain.ids import IdempotencyKey, MemoryCandidateId
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.values import RedactionReport, SourceRef
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryCommandIdempotencyRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryMemoryRepository,
    InMemoryOutboxRepository,
)

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service():
    candidate_repository = InMemoryMemoryCandidateRepository()
    memory_repository = InMemoryMemoryRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = PublishMemoryService(candidate_repository, memory_repository, InMemoryCommandIdempotencyRepository(), outbox_repository, SystemClockAdapter())
    return service, candidate_repository, memory_repository, outbox_repository


def _seed_validated_candidate(candidate_repository: InMemoryMemoryCandidateRepository) -> MemoryCandidateId:
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "text", _now())
    candidate = candidate.redact("text", RedactionReport()).validate(confidence_score=0.8, source_refs_trusted=True)
    candidate_repository.save(candidate, expected_status=None)
    return candidate.candidate_id


def _command(candidate_id: MemoryCandidateId, idempotency_key: str = "publish-1") -> PublishMemoryCommand:
    return PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="admin-1", idempotency_key=IdempotencyKey(idempotency_key),
        content="full resolution content", summary="short summary", source_trust_score=0.9,
    )


def test_publish_creates_memory_and_active_version_in_one_step() -> None:
    service, candidate_repository, memory_repository, outbox_repository = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)

    view = service.publish(_command(candidate_id))

    assert view.status.name == "ACTIVE"
    assert view.version == 1
    stored = memory_repository.find_active_version(view.memory_id)
    assert stored is not None and stored.memory_version_id == view.memory_version_id
    assert candidate_repository.find_by_id(candidate_id).status.name == "PUBLISHED"
    assert any(r.event_type == "memory.published.v1" for r in outbox_repository.recorded())


def test_publish_requires_an_approvable_candidate_status() -> None:
    service, candidate_repository, _, _ = _build_service()
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "text", _now())
    candidate_repository.save(candidate, expected_status=None)  # still EXTRACTED

    with pytest.raises(InvalidMemoryCandidateTransitionException):
        service.publish(_command(candidate.candidate_id))


def test_publish_is_idempotent_under_the_same_key() -> None:
    service, candidate_repository, _, outbox_repository = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)

    first = service.publish(_command(candidate_id, "publish-dup"))
    second = service.publish(_command(candidate_id, "publish-dup"))

    assert first.memory_version_id == second.memory_version_id
    assert len([r for r in outbox_repository.recorded() if r.event_type == "memory.published.v1"]) == 1


def test_publish_unknown_candidate_raises_not_found() -> None:
    service, _, _, _ = _build_service()

    with pytest.raises(MemoryCandidateNotFoundException):
        service.publish(_command(MemoryCandidateId.new_id()))
