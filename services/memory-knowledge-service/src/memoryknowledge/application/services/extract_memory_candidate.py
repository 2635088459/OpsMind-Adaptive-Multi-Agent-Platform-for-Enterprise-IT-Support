"""13-package-and-class-design §"Application Layer": ExtractMemoryCandidateService, the
sole implementation of ExtractMemoryCandidateUseCase. Source-agnostic pipeline entry
point — see ExtractMemoryCandidateCommand's own docstring for why event-driven
extraction from ticket.resolved.v1/workflow.completed.v1 is out of this spec's scope.
"""

from __future__ import annotations

import dataclasses
import hashlib
import uuid
from datetime import datetime

from memoryknowledge.application.commands import ExtractMemoryCandidateCommand
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    CommandIdempotencyRepository,
    MemoryCandidateRepository,
    OutboxRepository,
)
from memoryknowledge.application.services.audit import AuditRecorder
from memoryknowledge.application.services.idempotency import CommandIdempotencyGuard
from memoryknowledge.application.views import MemoryCandidateView
from memoryknowledge.domain.enums import MemoryCandidateStatus
from memoryknowledge.domain.events import MemoryCandidateCreated
from memoryknowledge.domain.ids import MemoryCandidateId, MemoryId
from memoryknowledge.domain.memory_candidate import MemoryCandidate

_COMMAND_TYPE = "extract_memory_candidate"


class ExtractMemoryCandidateService:
    def __init__(
        self, memory_candidate_repository: MemoryCandidateRepository, command_idempotency_repository: CommandIdempotencyRepository,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._memory_candidate_repository = memory_candidate_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def extract(self, command: ExtractMemoryCandidateCommand) -> MemoryCandidateView:
        """SPEC-MK-003 09-concurrency-and-idempotency §"Command Idempotency": a retried
        delivery under the same idempotency_key replays the prior response; the same
        key with a *different* candidate_text/source_refs/memory_type raises
        IdempotencyKeyReusedException instead of silently returning the old candidate.
        """
        return self._idempotency_guard.run(
            command_type=_COMMAND_TYPE, target_id=None, idempotency_key=command.idempotency_key,
            request_payload=_request_payload(command), execute=lambda: self._do_extract(command),
            to_dict=_view_to_dict, from_dict=_view_from_dict,
        )

    def _do_extract(self, command: ExtractMemoryCandidateCommand) -> MemoryCandidateView:
        # 08-transaction-and-outbox §"Candidate Extraction Transaction" step 2: "计算
        # sourceHash". 07-data-model `memory.memory_candidates` §"唯一键": `source_hash,
        # memory_type`; 09-concurrency-and-idempotency §"Candidate 并发": "sourceHash +
        # memoryType 唯一约束防重复; 重复请求返回已有 candidate" — an "upsert", not a second
        # candidate: a different caller (e.g. a different eventId under
        # SPEC-MK-010's own event consumers) extracting the same underlying evidence text
        # converges on the one candidate that already exists for it, instead of the
        # CommandIdempotencyGuard's own caller-supplied-key layer being the only defense.
        now = self._clock.now()
        source_hash = hashlib.sha256(command.candidate_text.encode()).hexdigest()
        existing = self._memory_candidate_repository.find_by_source_hash(source_hash, command.memory_type)
        if existing is not None:
            return MemoryCandidateView.from_domain(existing)

        candidate = MemoryCandidate.extract(
            MemoryCandidateId.new_id(), command.memory_type, command.source_refs, command.candidate_text, source_hash, now,
        )
        saved = self._memory_candidate_repository.save(candidate, expected_status=None)
        self._outbox_repository.append(build_outbox_record(
            MemoryCandidateCreated(candidate_id=saved.candidate_id, memory_type=saved.memory_type.name, occurred_at=now),
            "memory.candidate.created.v1", aggregate_id=str(saved.candidate_id), occurred_at=now,
        ))
        self._audit_recorder.record(
            audit_type="MEMORY", action="extract_candidate", resource_type="MEMORY_CANDIDATE", resource_id=str(saved.candidate_id),
            outcome="SUCCESS", actor_id=command.extracted_by,
        )
        return MemoryCandidateView.from_domain(saved)


def _request_payload(command: ExtractMemoryCandidateCommand) -> dict:
    return {
        "memory_type": command.memory_type.name, "candidate_text": command.candidate_text,
        "source_refs": [dataclasses.asdict(r) for r in command.source_refs], "extracted_by": command.extracted_by,
    }


def _view_to_dict(view: MemoryCandidateView) -> dict:
    return {
        "candidate_id": str(view.candidate_id.value), "status": view.status.name, "memory_type": view.memory_type,
        "confidence_score": view.confidence_score, "usefulness_score": view.usefulness_score,
        "review_required": view.review_required,
        "duplicate_of_memory_id": str(view.duplicate_of_memory_id.value) if view.duplicate_of_memory_id else None,
        "conflict_set_id": view.conflict_set_id, "rejection_reason": view.rejection_reason,
        "created_at": view.created_at.isoformat(),
    }


def _view_from_dict(data: dict) -> MemoryCandidateView:
    return MemoryCandidateView(
        candidate_id=MemoryCandidateId(uuid.UUID(data["candidate_id"])), status=MemoryCandidateStatus[data["status"]],
        memory_type=data["memory_type"], confidence_score=data["confidence_score"], usefulness_score=data["usefulness_score"],
        review_required=data["review_required"],
        duplicate_of_memory_id=MemoryId(uuid.UUID(data["duplicate_of_memory_id"])) if data["duplicate_of_memory_id"] else None,
        conflict_set_id=data["conflict_set_id"], rejection_reason=data["rejection_reason"],
        created_at=datetime.fromisoformat(data["created_at"]),
    )
