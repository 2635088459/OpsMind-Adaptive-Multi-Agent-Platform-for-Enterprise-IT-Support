"""13-package-and-class-design §"Application Layer": ExtractMemoryCandidateService, the
sole implementation of ExtractMemoryCandidateUseCase. Source-agnostic pipeline entry
point — see ExtractMemoryCandidateCommand's own docstring for why event-driven
extraction from ticket.resolved.v1/workflow.completed.v1 is out of this spec's scope.
"""

from __future__ import annotations

import uuid

from memoryknowledge.application.commands import ExtractMemoryCandidateCommand
from memoryknowledge.application.exceptions import MemoryCandidateNotFoundException
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import ClockPort, CommandIdempotencyRepository, MemoryCandidateRepository, OutboxRepository
from memoryknowledge.application.records import CommandIdempotencyRecord
from memoryknowledge.application.views import MemoryCandidateView
from memoryknowledge.domain.events import MemoryCandidateCreated
from memoryknowledge.domain.ids import MemoryCandidateId
from memoryknowledge.domain.memory_candidate import MemoryCandidate

_COMMAND_TYPE = "extract_memory_candidate"


class ExtractMemoryCandidateService:
    def __init__(
        self, memory_candidate_repository: MemoryCandidateRepository, command_idempotency_repository: CommandIdempotencyRepository,
        outbox_repository: OutboxRepository, clock: ClockPort,
    ) -> None:
        self._memory_candidate_repository = memory_candidate_repository
        self._command_idempotency_repository = command_idempotency_repository
        self._outbox_repository = outbox_repository
        self._clock = clock

    def extract(self, command: ExtractMemoryCandidateCommand) -> MemoryCandidateView:
        """SPEC-MK-001 domain-rules: "需要写状态的命令必须具备幂等或版本保护" — a retried
        delivery under the same idempotency_key replays the prior result rather than
        creating a second candidate.
        """
        existing = self._command_idempotency_repository.find_by_key(command.idempotency_key)
        if existing is not None:
            existing_id = MemoryCandidateId(uuid.UUID(existing.result_ref))
            candidate = self._memory_candidate_repository.find_by_id(existing_id)
            if candidate is None:
                raise MemoryCandidateNotFoundException(existing_id)
            return MemoryCandidateView.from_domain(candidate)

        now = self._clock.now()
        candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), command.memory_type, command.source_refs, command.candidate_text, now)
        saved = self._memory_candidate_repository.save(candidate, expected_status=None)
        self._command_idempotency_repository.save(
            CommandIdempotencyRecord(command.idempotency_key, _COMMAND_TYPE, str(saved.candidate_id), now)
        )
        self._outbox_repository.append(build_outbox_record(
            MemoryCandidateCreated(candidate_id=saved.candidate_id, memory_type=saved.memory_type.name, occurred_at=now),
            "memory.candidate.created.v1", aggregate_id=str(saved.candidate_id), occurred_at=now,
        ))
        return MemoryCandidateView.from_domain(saved)
