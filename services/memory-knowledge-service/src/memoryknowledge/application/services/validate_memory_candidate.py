"""13-package-and-class-design §"Application Layer": ValidateMemoryCandidateService, the
sole implementation of ValidateMemoryCandidateUseCase. Drives
EXTRACTED -> REDACTED -> VALIDATED -> {VALIDATED | DUPLICATE | CONFLICTING} as one
orchestrated step (03-state-machine), plus the separate reject() transition.
"""

from __future__ import annotations

import hashlib

from memoryknowledge.application.commands import RejectMemoryCandidateCommand, ValidateMemoryCandidateCommand
from memoryknowledge.application.exceptions import MemoryCandidateNotFoundException
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import ClockPort, MemoryCandidateRepository, MemoryRepository, OutboxRepository, RedactionPolicyPort
from memoryknowledge.application.views import MemoryCandidateView
from memoryknowledge.domain.events import MemoryCandidateRejected


class ValidateMemoryCandidateService:
    def __init__(
        self, memory_candidate_repository: MemoryCandidateRepository, memory_repository: MemoryRepository,
        redaction_policy_port: RedactionPolicyPort, outbox_repository: OutboxRepository, clock: ClockPort,
    ) -> None:
        self._memory_candidate_repository = memory_candidate_repository
        self._memory_repository = memory_repository
        self._redaction_policy_port = redaction_policy_port
        self._outbox_repository = outbox_repository
        self._clock = clock

    def validate(self, command: ValidateMemoryCandidateCommand) -> MemoryCandidateView:
        candidate = self._memory_candidate_repository.find_by_id(command.candidate_id)
        if candidate is None:
            raise MemoryCandidateNotFoundException(command.candidate_id)

        # 03-state-machine: "EXTRACTED -> REDACTED 必须生成 redaction report."
        previous_status = candidate.status
        redacted_text, report = self._redaction_policy_port.redact(candidate.candidate_text)
        candidate = candidate.redact(redacted_text, report)
        candidate = self._memory_candidate_repository.save(candidate, previous_status)

        # 03-state-machine: "REDACTED -> VALIDATED 必须校验 source refs 存在且可信."
        previous_status = candidate.status
        candidate = candidate.validate(command.confidence_score, command.source_refs_trusted)
        candidate = self._memory_candidate_repository.save(candidate, previous_status)

        if command.conflict_set_id is not None:
            # 02-business-invariants: "CONFLICTING candidate 必须人工或 policy 处理，不能自动
            # 覆盖 active memory" — see ValidateMemoryCandidateCommand.conflict_set_id's
            # own docstring for why this is caller-flagged rather than auto-detected.
            previous_status = candidate.status
            candidate = candidate.mark_conflicting(command.conflict_set_id)
            candidate = self._memory_candidate_repository.save(candidate, previous_status)
            return MemoryCandidateView.from_domain(candidate)

        # 02-business-invariants: "DUPLICATE candidate 不能创建新的 Memory，只能链接到既有
        # Memory" — dedup by an exact-match hash of the redacted text against existing
        # active memory content. A real similarity-based dedup is phase-03
        # (memory-candidate-pipeline) scope; this exact-hash check is honest, not fabricated,
        # for the cases it does catch.
        source_hash = hashlib.sha256(candidate.redacted_text.encode()).hexdigest()
        existing_version = self._memory_repository.find_by_source_hash(source_hash)
        if existing_version is not None:
            previous_status = candidate.status
            candidate = candidate.mark_duplicate(existing_version.memory_id)
            candidate = self._memory_candidate_repository.save(candidate, previous_status)

        return MemoryCandidateView.from_domain(candidate)

    def reject(self, command: RejectMemoryCandidateCommand) -> MemoryCandidateView:
        candidate = self._memory_candidate_repository.find_by_id(command.candidate_id)
        if candidate is None:
            raise MemoryCandidateNotFoundException(command.candidate_id)

        previous_status = candidate.status
        candidate = candidate.reject(command.reason)
        candidate = self._memory_candidate_repository.save(candidate, previous_status)

        now = self._clock.now()
        self._outbox_repository.append(build_outbox_record(
            MemoryCandidateRejected(candidate_id=candidate.candidate_id, reason=command.reason, occurred_at=now),
            "memory.candidate.rejected.v1", aggregate_id=str(candidate.candidate_id), occurred_at=now,
        ))
        return MemoryCandidateView.from_domain(candidate)
