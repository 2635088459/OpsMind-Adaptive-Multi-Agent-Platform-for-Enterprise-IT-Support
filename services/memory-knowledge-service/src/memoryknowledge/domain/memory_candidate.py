"""01-domain-model §"MemoryCandidate": extracted from ticket/workflow/tool
evidence/human feedback/evaluation. 03-state-machine §"Memory Candidate 状态机" is the
transition graph this module implements exactly:

EXTRACTED -> REDACTED -> VALIDATED -> APPROVED -> PUBLISHED
EXTRACTED / REDACTED / VALIDATED / CONFLICTING -> REJECTED
VALIDATED -> DUPLICATE
VALIDATED -> CONFLICTING
CONFLICTING -> APPROVED
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.enums import MemoryCandidateStatus, MemoryType
from memoryknowledge.domain.exceptions import InvalidMemoryCandidateTransitionException, MemoryCandidateMissingSourceRefException
from memoryknowledge.domain.ids import MemoryCandidateId, MemoryId
from memoryknowledge.domain.values import RedactionReport, SourceRef

_REDACTABLE = frozenset({MemoryCandidateStatus.EXTRACTED})
_VALIDATABLE = frozenset({MemoryCandidateStatus.REDACTED})
_DEDUP_DECIDABLE = frozenset({MemoryCandidateStatus.VALIDATED})
_APPROVABLE = frozenset({MemoryCandidateStatus.VALIDATED, MemoryCandidateStatus.CONFLICTING})
_REJECTABLE = frozenset({
    MemoryCandidateStatus.EXTRACTED, MemoryCandidateStatus.REDACTED,
    MemoryCandidateStatus.VALIDATED, MemoryCandidateStatus.CONFLICTING,
})
_PUBLISHABLE = frozenset({MemoryCandidateStatus.APPROVED})


@dataclass(frozen=True, slots=True)
class MemoryCandidate:
    candidate_id: MemoryCandidateId
    memory_type: MemoryType
    status: MemoryCandidateStatus
    source_refs: tuple[SourceRef, ...]
    candidate_text: str
    redacted_text: str | None
    redaction_report: RedactionReport | None
    confidence_score: float | None
    usefulness_score: float | None
    duplicate_of_memory_id: MemoryId | None
    conflict_set_id: str | None
    review_required: bool
    rejection_reason: str | None
    created_at: datetime

    @staticmethod
    def extract(
        candidate_id: MemoryCandidateId, memory_type: MemoryType, source_refs: tuple[SourceRef, ...],
        candidate_text: str, created_at: datetime,
    ) -> "MemoryCandidate":
        """02-business-invariants: "Candidate ... 都必须有 source/evidence"."""
        if not source_refs:
            raise MemoryCandidateMissingSourceRefException()
        return MemoryCandidate(
            candidate_id=candidate_id, memory_type=memory_type, status=MemoryCandidateStatus.EXTRACTED,
            source_refs=source_refs, candidate_text=candidate_text, redacted_text=None, redaction_report=None,
            confidence_score=None, usefulness_score=None, duplicate_of_memory_id=None, conflict_set_id=None,
            review_required=False, rejection_reason=None, created_at=created_at,
        )

    def redact(self, redacted_text: str, redaction_report: RedactionReport) -> "MemoryCandidate":
        """03-state-machine: "EXTRACTED -> REDACTED 必须生成 redaction report"."""
        if self.status not in _REDACTABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _REDACTABLE)
        return dataclasses.replace(
            self, status=MemoryCandidateStatus.REDACTED, redacted_text=redacted_text, redaction_report=redaction_report,
        )

    def validate(self, confidence_score: float, source_refs_trusted: bool) -> "MemoryCandidate":
        """03-state-machine: "REDACTED -> VALIDATED 必须校验 source refs 存在且可信" — the
        trust check itself is an I/O-backed lookup the application service performs (e.g.
        against TicketSnapshotPort/WorkflowTracePort) and passes in as a plain bool; this
        method only enforces that a validated candidate cannot lack refs or trust.
        """
        if self.status not in _VALIDATABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _VALIDATABLE)
        if not self.source_refs or not source_refs_trusted:
            raise MemoryCandidateMissingSourceRefException()
        return dataclasses.replace(self, status=MemoryCandidateStatus.VALIDATED, confidence_score=confidence_score)

    def mark_duplicate(self, duplicate_of_memory_id: MemoryId) -> "MemoryCandidate":
        """03-state-machine: "VALIDATED -> DUPLICATE 必须记录 duplicateOfMemoryId".
        02-business-invariants: "DUPLICATE candidate 不能创建新的 Memory，只能链接到既有
        Memory."
        """
        if self.status not in _DEDUP_DECIDABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _DEDUP_DECIDABLE)
        return dataclasses.replace(self, status=MemoryCandidateStatus.DUPLICATE, duplicate_of_memory_id=duplicate_of_memory_id)

    def mark_conflicting(self, conflict_set_id: str) -> "MemoryCandidate":
        """03-state-machine: "VALIDATED -> CONFLICTING 必须记录 conflictSetId".
        02-business-invariants: "CONFLICTING candidate 必须人工或 policy 处理，不能自动覆盖
        active memory" — review_required flips to True here so the pipeline cannot silently
        auto-approve a conflicting candidate.
        """
        if self.status not in _DEDUP_DECIDABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _DEDUP_DECIDABLE)
        return dataclasses.replace(
            self, status=MemoryCandidateStatus.CONFLICTING, conflict_set_id=conflict_set_id, review_required=True,
        )

    def approve(self, usefulness_score: float) -> "MemoryCandidate":
        """03-state-machine: "VALIDATED -> APPROVED"; "CONFLICTING -> APPROVED" (only after
        the conflict has been resolved by a human/policy — enforced by the application
        service requiring an explicit approval command, never automatic).
        """
        if self.status not in _APPROVABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _APPROVABLE)
        return dataclasses.replace(self, status=MemoryCandidateStatus.APPROVED, usefulness_score=usefulness_score, review_required=False)

    def reject(self, reason: str) -> "MemoryCandidate":
        """03-state-machine: "EXTRACTED / REDACTED / VALIDATED -> REJECTED", extended to
        CONFLICTING (a conflicting candidate that a human/policy decides not to approve).
        """
        if self.status not in _REJECTABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _REJECTABLE)
        return dataclasses.replace(self, status=MemoryCandidateStatus.REJECTED, rejection_reason=reason)

    def publish(self) -> "MemoryCandidate":
        """03-state-machine: "APPROVED -> PUBLISHED 必须在同一事务中创建 MemoryVersion 和
        outbox event" — this flip is that same transaction's candidate-side half; the
        MemoryVersion/outbox creation itself is domain.memory.MemoryVersion.create_active().
        """
        if self.status not in _PUBLISHABLE:
            raise InvalidMemoryCandidateTransitionException(self.status, _PUBLISHABLE)
        return dataclasses.replace(self, status=MemoryCandidateStatus.PUBLISHED)
