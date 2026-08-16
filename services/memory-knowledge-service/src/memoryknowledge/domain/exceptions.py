"""Domain exceptions — thrown by domain aggregates when a rule is violated using only
information the aggregate already carries (no I/O, no repository lookups).

Distinct from application-layer exceptions (memoryknowledge.application.exceptions),
which are raised after I/O a pure domain function must not perform (repository
lookups, uniqueness checks, persisted-version comparisons against a freshly-read row).
"""

from __future__ import annotations

from memoryknowledge.domain.enums import (
    DocumentIngestionStatus,
    GraphNodeStatus,
    MemoryCandidateStatus,
    MemoryVersionStatus,
    WorkingMemoryStatus,
)


class InvalidMemoryCandidateTransitionException(RuntimeError):
    """03-state-machine §"Memory Candidate 状态机": attempted transition from a status
    that does not allow it.
    """

    def __init__(self, current_status: MemoryCandidateStatus, allowed: frozenset[MemoryCandidateStatus]) -> None:
        super().__init__(f"memory candidate is in status {current_status} but requires one of {allowed}")
        self.current_status = current_status
        self.allowed = allowed


class MemoryCandidateMissingSourceRefException(RuntimeError):
    """02-business-invariants: "Candidate ... 都必须有 source/evidence"."""

    def __init__(self) -> None:
        super().__init__("memory candidate must have at least one sourceRef")


class InvalidMemoryVersionTransitionException(RuntimeError):
    """03-state-machine §"Memory Version 状态机"."""

    def __init__(self, current_status: MemoryVersionStatus, allowed: frozenset[MemoryVersionStatus]) -> None:
        super().__init__(f"memory version is in status {current_status} but requires one of {allowed}")
        self.current_status = current_status
        self.allowed = allowed


class MemoryVersionMissingSourceRefException(RuntimeError):
    """02-business-invariants: "Active MemoryVersion 必须至少有一个 SourceRef"."""

    def __init__(self) -> None:
        super().__init__("active memory version must have at least one sourceRef")


class InvalidDocumentIngestionTransitionException(RuntimeError):
    """03-state-machine §"Knowledge Document Ingestion 状态机"."""

    def __init__(self, current_status: DocumentIngestionStatus, allowed: frozenset[DocumentIngestionStatus]) -> None:
        super().__init__(f"knowledge document is in status {current_status} but requires one of {allowed}")
        self.current_status = current_status
        self.allowed = allowed


class InvalidWorkingMemoryStateException(RuntimeError):
    """03-state-machine §"Working Memory 状态": update/archive attempted on a
    non-ACTIVE Working Memory.
    """

    def __init__(self, current_status: WorkingMemoryStatus) -> None:
        super().__init__(f"working memory is in status {current_status} but requires ACTIVE")
        self.current_status = current_status


class WorkingMemoryVersionConflictException(RuntimeError):
    """01-domain-model §"WorkingMemory": "更新必须使用 optimistic version." Raised inside
    the domain object itself (unlike the persisted-row version conflicts
    agent-runtime-service's own application layer raises) because Working Memory
    carries its own version as a plain field — the comparison needs no I/O.
    """

    def __init__(self, expected_version: int, actual_version: int) -> None:
        super().__init__(f"expected working memory version {expected_version} but it is at {actual_version}")
        self.expected_version = expected_version
        self.actual_version = actual_version


class GraphEdgeMissingEvidenceException(RuntimeError):
    """02-business-invariants: "Graph edge 必须有 evidenceRefs 和 confidence，不能保存无来源
    关系."
    """

    def __init__(self) -> None:
        super().__init__("graph edge must have at least one evidenceRef")


class InvalidGraphNodeTransitionException(RuntimeError):
    """03-state-machine §"Graph Index 状态": TOMBSTONED is one-way."""

    def __init__(self, current_status: GraphNodeStatus) -> None:
        super().__init__(f"graph node is in status {current_status} and cannot transition further")
        self.current_status = current_status
