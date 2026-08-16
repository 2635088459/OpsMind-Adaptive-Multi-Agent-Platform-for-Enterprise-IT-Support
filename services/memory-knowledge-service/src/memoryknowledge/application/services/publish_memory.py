"""13-package-and-class-design §"Application Layer": PublishMemoryService, the sole
implementation of PublishMemoryUseCase. 03-state-machine: "APPROVED -> PUBLISHED 必须在
同一事务中创建 MemoryVersion 和 outbox event" — approve() and publish() both happen here,
in one command, alongside the Memory/MemoryVersion creation and outbox append (the
"transaction" is this spec's in-memory adapters; SPEC-MK-002 gives it a real Postgres
transaction boundary).
"""

from __future__ import annotations

import hashlib
import uuid

from memoryknowledge.application.commands import PublishMemoryCommand
from memoryknowledge.application.exceptions import MemoryCandidateNotFoundException
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import ClockPort, CommandIdempotencyRepository, MemoryCandidateRepository, MemoryRepository, OutboxRepository
from memoryknowledge.application.records import CommandIdempotencyRecord
from memoryknowledge.application.views import MemoryVersionView
from memoryknowledge.domain.events import MemoryPublished
from memoryknowledge.domain.ids import MemoryId, MemoryVersionId
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import RedactionReport

_COMMAND_TYPE = "publish_memory"


class PublishMemoryService:
    def __init__(
        self, memory_candidate_repository: MemoryCandidateRepository, memory_repository: MemoryRepository,
        command_idempotency_repository: CommandIdempotencyRepository, outbox_repository: OutboxRepository, clock: ClockPort,
    ) -> None:
        self._memory_candidate_repository = memory_candidate_repository
        self._memory_repository = memory_repository
        self._command_idempotency_repository = command_idempotency_repository
        self._outbox_repository = outbox_repository
        self._clock = clock

    def publish(self, command: PublishMemoryCommand) -> MemoryVersionView:
        existing = self._command_idempotency_repository.find_by_key(command.idempotency_key)
        if existing is not None:
            memory_version_id = MemoryVersionId(uuid.UUID(existing.result_ref))
            version = self._memory_repository.find_version_by_id(memory_version_id)
            if version is not None:
                return MemoryVersionView.from_domain(version)

        candidate = self._memory_candidate_repository.find_by_id(command.candidate_id)
        if candidate is None:
            raise MemoryCandidateNotFoundException(command.candidate_id)

        previous_status = candidate.status
        candidate = candidate.approve(command.usefulness_score)
        candidate = candidate.publish()
        candidate = self._memory_candidate_repository.save(candidate, previous_status)

        now = self._clock.now()
        memory = Memory.create(MemoryId.new_id(), candidate.memory_type, now)
        memory = self._memory_repository.save_memory(memory)

        source_hash = hashlib.sha256(command.content.encode()).hexdigest()
        version = MemoryVersion.create_active(
            memory_version_id=MemoryVersionId.new_id(), memory_id=memory.memory_id, version=1, content=command.content,
            summary=command.summary, source_refs=candidate.source_refs,
            redaction_report=candidate.redaction_report or RedactionReport(), confidence_score=candidate.confidence_score or 0.0,
            source_trust_score=command.source_trust_score, source_hash=source_hash, created_by=command.published_by, created_at=now,
        )
        version = self._memory_repository.save_version(version, expected_status=None)

        self._command_idempotency_repository.save(
            CommandIdempotencyRecord(command.idempotency_key, _COMMAND_TYPE, str(version.memory_version_id), now)
        )
        self._outbox_repository.append(build_outbox_record(
            MemoryPublished(memory_id=memory.memory_id, memory_version_id=version.memory_version_id, version=version.version, occurred_at=now),
            "memory.published.v1", aggregate_id=str(memory.memory_id), occurred_at=now,
        ))
        return MemoryVersionView.from_domain(version)
