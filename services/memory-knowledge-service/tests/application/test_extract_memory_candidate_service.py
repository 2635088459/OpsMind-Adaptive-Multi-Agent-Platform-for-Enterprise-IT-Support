from __future__ import annotations

import pytest

from memoryknowledge.application.commands import ExtractMemoryCandidateCommand
from memoryknowledge.application.services.extract_memory_candidate import ExtractMemoryCandidateService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import IdempotencyKey
from memoryknowledge.domain.values import SourceRef
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryOutboxRepository,
)

pytestmark = pytest.mark.unit


def _build_service():
    candidate_repository = InMemoryMemoryCandidateRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = ExtractMemoryCandidateService(
        candidate_repository, InMemoryCommandIdempotencyRepository(), outbox_repository, InMemoryAuditRecordRepository(), SystemClockAdapter(),
        MemoryTelemetry(),
    )
    return service, candidate_repository, outbox_repository


def _command(idempotency_key: str = "extract-1", candidate_text: str = "vpn fails after mfa reset") -> ExtractMemoryCandidateCommand:
    return ExtractMemoryCandidateCommand(
        memory_type=MemoryType.EPISODIC, source_refs=(SourceRef("ticket", "T-1"),), candidate_text=candidate_text,
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


def test_a_different_idempotency_key_with_different_content_creates_a_new_candidate() -> None:
    service, _, _ = _build_service()

    first = service.extract(_command("extract-a", candidate_text="vpn fails after mfa reset"))
    second = service.extract(_command("extract-b", candidate_text="printer offline on floor 3"))

    assert first.candidate_id != second.candidate_id


def test_a_different_idempotency_key_with_identical_content_converges_on_the_same_candidate() -> None:
    """07-data-model `memory.memory_candidates` §"唯一键": `source_hash, memory_type`.
    09-concurrency-and-idempotency §"Candidate 并发": "sourceHash + memoryType 唯一约束
    防重复; 重复请求返回已有 candidate" — this natural-key dedup is a separate layer from
    CommandIdempotencyGuard's own caller-supplied-key replay (tested above): two
    *different* idempotency keys extracting byte-identical evidence must still converge
    on one candidate, e.g. two of SPEC-MK-010's own event consumers reporting the same
    underlying ticket outcome under different eventIds.
    """
    service, candidate_repository, outbox_repository = _build_service()

    first = service.extract(_command("extract-a", candidate_text="vpn fails after mfa reset"))
    second = service.extract(_command("extract-b", candidate_text="vpn fails after mfa reset"))

    assert first.candidate_id == second.candidate_id
    assert len([r for r in outbox_repository.recorded() if r.event_type == "memory.candidate.created.v1"]) == 1
