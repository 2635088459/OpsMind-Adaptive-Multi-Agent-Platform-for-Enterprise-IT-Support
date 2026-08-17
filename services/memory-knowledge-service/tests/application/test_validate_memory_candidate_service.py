from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import RejectMemoryCandidateCommand, ValidateMemoryCandidateCommand
from memoryknowledge.application.services.validate_memory_candidate import ValidateMemoryCandidateService
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import MemoryCandidateId, MemoryId, MemoryVersionId
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.values import RedactionReport, SourceRef
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryMemoryRepository,
    InMemoryOutboxRepository,
)
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service():
    candidate_repository = InMemoryMemoryCandidateRepository()
    memory_repository = InMemoryMemoryRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = ValidateMemoryCandidateService(
        candidate_repository, memory_repository, RegexRedactionPolicyAdapter(), outbox_repository,
        InMemoryAuditRecordRepository(), SystemClockAdapter(),
    )
    return service, candidate_repository, memory_repository, outbox_repository


def _seed_candidate(candidate_repository: InMemoryMemoryCandidateRepository, text: str = "vpn fails after mfa reset") -> MemoryCandidateId:
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), text, "hash-1", _now())
    candidate_repository.save(candidate, expected_status=None)
    return candidate.candidate_id


def test_validate_moves_extracted_to_validated_with_a_redaction_report() -> None:
    service, candidate_repository, _, _ = _build_service()
    candidate_id = _seed_candidate(candidate_repository)

    view = service.validate(ValidateMemoryCandidateCommand(candidate_id=candidate_id, source_refs_trusted=True, confidence_score=0.8))

    assert view.status.name == "VALIDATED"
    assert view.confidence_score == 0.8


def test_validate_with_untrusted_refs_is_rejected() -> None:
    from memoryknowledge.domain.exceptions import MemoryCandidateMissingSourceRefException

    service, candidate_repository, _, _ = _build_service()
    candidate_id = _seed_candidate(candidate_repository)

    with pytest.raises(MemoryCandidateMissingSourceRefException):
        service.validate(ValidateMemoryCandidateCommand(candidate_id=candidate_id, source_refs_trusted=False, confidence_score=0.8))


def test_validate_flags_conflicting_when_caller_provides_a_conflict_set() -> None:
    service, candidate_repository, _, _ = _build_service()
    candidate_id = _seed_candidate(candidate_repository)

    view = service.validate(ValidateMemoryCandidateCommand(
        candidate_id=candidate_id, source_refs_trusted=True, confidence_score=0.8, conflict_set_id="conflict-1",
    ))

    assert view.status.name == "CONFLICTING"
    assert view.review_required is True
    assert view.conflict_set_id == "conflict-1"


def test_validate_marks_duplicate_when_content_hash_matches_an_existing_active_memory() -> None:
    service, candidate_repository, memory_repository, _ = _build_service()
    redacted_text = "vpn fails after mfa reset"
    import hashlib

    source_hash = hashlib.sha256(redacted_text.encode()).hexdigest()
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now())
    memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 1, redacted_text, redacted_text, (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.9, 0.9, source_hash, "agent-1", _now(),
    )
    memory_repository.save_version(version, expected_status=None)

    candidate_id = _seed_candidate(candidate_repository, text=redacted_text)
    view = service.validate(ValidateMemoryCandidateCommand(candidate_id=candidate_id, source_refs_trusted=True, confidence_score=0.9))

    assert view.status.name == "DUPLICATE"
    assert view.duplicate_of_memory_id == memory.memory_id


def test_reject_publishes_candidate_rejected_event() -> None:
    service, candidate_repository, _, outbox_repository = _build_service()
    candidate_id = _seed_candidate(candidate_repository)

    view = service.reject(RejectMemoryCandidateCommand(candidate_id=candidate_id, reason="no evidence", actor_id="agent-1"))

    assert view.status.name == "REJECTED"
    assert view.rejection_reason == "no evidence"
    assert any(r.event_type == "memory.candidate.rejected.v1" for r in outbox_repository.recorded())
