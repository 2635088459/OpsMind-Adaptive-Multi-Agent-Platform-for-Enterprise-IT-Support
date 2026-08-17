"""01-domain-model §"Memory" / §"MemoryVersion": long-term memory's logical identity
(Memory) and its immutable, versioned content (MemoryVersion).
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.enums import MemoryType, MemoryVersionStatus
from memoryknowledge.domain.exceptions import InvalidMemoryVersionTransitionException, MemoryVersionMissingSourceRefException
from memoryknowledge.domain.ids import MemoryId, MemoryVersionId
from memoryknowledge.domain.values import EmbeddingRef, RedactionReport, SourceRef

_SUPERSEDABLE_STATUSES = frozenset({MemoryVersionStatus.ACTIVE})
_DEPRECATABLE_STATUSES = frozenset({MemoryVersionStatus.ACTIVE})
_DELETABLE_STATUSES = frozenset({MemoryVersionStatus.ACTIVE, MemoryVersionStatus.SUPERSEDED, MemoryVersionStatus.DEPRECATED})


@dataclass(frozen=True, slots=True)
class Memory:
    """01-domain-model: "长期记忆的逻辑身份。当前有效内容由 MemoryVersion 表达." 07-data-model
    `memory.memories` §"classification text not null" — 02-business-invariants:
    "高敏 classification 的 memory 默认不可跨 queue / role 检索"; 11-security §"检索前必须
    计算 access scope，并应用到：Memory classification."
    """

    memory_id: MemoryId
    memory_type: MemoryType
    classification: str
    created_at: datetime

    @staticmethod
    def create(memory_id: MemoryId, memory_type: MemoryType, created_at: datetime, classification: str = "INTERNAL") -> "Memory":
        return Memory(memory_id=memory_id, memory_type=memory_type, classification=classification, created_at=created_at)


@dataclass(frozen=True, slots=True)
class MemoryVersion:
    memory_version_id: MemoryVersionId
    memory_id: MemoryId
    version: int
    status: MemoryVersionStatus
    content: str
    summary: str
    source_refs: tuple[SourceRef, ...]
    redaction_report: RedactionReport
    confidence_score: float
    source_trust_score: float
    embedding_ref: EmbeddingRef | None
    source_hash: str
    supersedes_version_id: MemoryVersionId | None
    created_by: str
    created_at: datetime

    @staticmethod
    def create_active(
        memory_version_id: MemoryVersionId,
        memory_id: MemoryId,
        version: int,
        content: str,
        summary: str,
        source_refs: tuple[SourceRef, ...],
        redaction_report: RedactionReport,
        confidence_score: float,
        source_trust_score: float,
        source_hash: str,
        created_by: str,
        created_at: datetime,
        embedding_ref: EmbeddingRef | None = None,
        supersedes_version_id: MemoryVersionId | None = None,
    ) -> "MemoryVersion":
        """03-state-machine: "APPROVED -> PUBLISHED 必须在同一事务中创建 MemoryVersion 和
        outbox event" — this factory is that MemoryVersion creation step.
        02-business-invariants: "Active MemoryVersion 必须至少有一个 SourceRef"; "长期记忆
        必须保存 redaction report"; "长期记忆必须有 confidenceScore 和 sourceTrustScore."
        """
        if not source_refs:
            raise MemoryVersionMissingSourceRefException()
        return MemoryVersion(
            memory_version_id=memory_version_id, memory_id=memory_id, version=version, status=MemoryVersionStatus.ACTIVE,
            content=content, summary=summary, source_refs=source_refs, redaction_report=redaction_report,
            confidence_score=confidence_score, source_trust_score=source_trust_score, embedding_ref=embedding_ref,
            source_hash=source_hash, supersedes_version_id=supersedes_version_id, created_by=created_by, created_at=created_at,
        )

    def supersede(self) -> "MemoryVersion":
        """03-state-machine: "ACTIVE -> SUPERSEDED"; "SUPERSEDED 必须指向新 active version"
        — the pointer lives on the *new* version's supersedes_version_id, not here.
        """
        if self.status not in _SUPERSEDABLE_STATUSES:
            raise InvalidMemoryVersionTransitionException(self.status, _SUPERSEDABLE_STATUSES)
        return dataclasses.replace(self, status=MemoryVersionStatus.SUPERSEDED)

    def deprecate(self) -> "MemoryVersion":
        """03-state-machine: "ACTIVE -> DEPRECATED"."""
        if self.status not in _DEPRECATABLE_STATUSES:
            raise InvalidMemoryVersionTransitionException(self.status, _DEPRECATABLE_STATUSES)
        return dataclasses.replace(self, status=MemoryVersionStatus.DEPRECATED)

    def delete(self) -> "MemoryVersion":
        """03-state-machine: "ACTIVE/SUPERSEDED/DEPRECATED -> DELETED"; "DELETED 不可检索，
        但保留 audit metadata". 11-security §"删除和保留": "删除成功后，默认保留 tombstone、
        audit、source hash，不保留可恢复原文" — content/summary are scrubbed here, mirroring
        WorkingMemory.delete()'s own tombstone-and-clear shape; source_refs/source_hash/
        confidence_score/source_trust_score/created_by/created_at are kept intact as the
        tombstone/audit trail that same line requires. Leaving content/summary readable in
        storage and relying only on the retrieval layer to filter DELETED status would
        keep a fully recoverable original text sitting in the row, which this line
        explicitly forbids.
        """
        if self.status not in _DELETABLE_STATUSES:
            raise InvalidMemoryVersionTransitionException(self.status, _DELETABLE_STATUSES)
        return dataclasses.replace(self, status=MemoryVersionStatus.DELETED, content="", summary="")
