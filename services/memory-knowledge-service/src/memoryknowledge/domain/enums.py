"""Domain enums. Pure stdlib — no framework dependency (SPEC-MK-001 domain-rules).

Member sets mirror docs/low-level-design/domains/04-memory-knowledge/01-domain-model
and 03-state-machine exactly, even though 03-state-machine is not itself in this
spec's LLD mapping (13-package-and-class-design, 02-business-invariants) — the
package boundary these enums live in has to be shaped so later phase specs
(phase-01 working-memory .. phase-05 retrieval-and-knowledge-graph) never need to
change it again, mirroring agent-runtime-service's own SPEC-ARO-001 WorkflowState
precedent.
"""

from __future__ import annotations

from enum import Enum, auto


class MemoryType(Enum):
    """01-domain-model §"Memory": the five long-term memory kinds."""

    EPISODIC = auto()
    SEMANTIC = auto()
    PROCEDURAL = auto()
    ORGANIZATIONAL = auto()
    AGENT_PERFORMANCE = auto()


class MemoryCandidateStatus(Enum):
    """03-state-machine §"Memory Candidate 状态机"."""

    EXTRACTED = auto()
    REDACTED = auto()
    VALIDATED = auto()
    DUPLICATE = auto()
    CONFLICTING = auto()
    APPROVED = auto()
    REJECTED = auto()
    PUBLISHED = auto()

    def is_terminal(self) -> bool:
        """02-business-invariants §"记忆写入不变量": PUBLISHED/REJECTED/DUPLICATE end the
        pipeline for a given candidate — DUPLICATE only ever links to an existing Memory,
        never creates a new one, so it terminates this candidate's own lifecycle even
        though it is not itself a Memory.
        """
        return self in _TERMINAL_CANDIDATE_STATUSES


_TERMINAL_CANDIDATE_STATUSES = frozenset({
    MemoryCandidateStatus.PUBLISHED, MemoryCandidateStatus.REJECTED, MemoryCandidateStatus.DUPLICATE,
})


class MemoryVersionStatus(Enum):
    """03-state-machine §"Memory Version 状态机"."""

    DRAFT = auto()
    ACTIVE = auto()
    SUPERSEDED = auto()
    DEPRECATED = auto()
    DELETED = auto()

    def is_default_retrievable(self) -> bool:
        """02-business-invariants §"检索不变量": only ACTIVE is returned to Agent retrieval
        by default; DEPRECATED stays admin-visible but out of default retrieval.
        """
        return self is MemoryVersionStatus.ACTIVE


class DocumentIngestionStatus(Enum):
    """03-state-machine §"Knowledge Document Ingestion 状态机"."""

    RECEIVED = auto()
    PARSED = auto()
    CHUNKED = auto()
    EMBEDDED = auto()
    INDEXED = auto()
    ACTIVE = auto()
    FAILED = auto()
    SUPERSEDED = auto()
    EXPIRED = auto()
    DELETED = auto()

    def is_retrievable(self) -> bool:
        return self is DocumentIngestionStatus.ACTIVE


class WorkingMemoryStatus(Enum):
    """03-state-machine §"Working Memory 状态": no complex state machine needed."""

    ACTIVE = auto()
    ARCHIVED = auto()
    DELETED = auto()


class GraphNodeStatus(Enum):
    """03-state-machine §"Graph Index 状态"."""

    VISIBLE = auto()
    HIDDEN = auto()
    TOMBSTONED = auto()


class GraphNodeType(Enum):
    """01-domain-model §"KnowledgeGraph": 核心节点类型."""

    TICKET = auto()
    WORKFLOW = auto()
    MEMORY = auto()
    MEMORY_VERSION = auto()
    DOCUMENT = auto()
    DOCUMENT_CHUNK = auto()
    SERVICE = auto()
    APPLICATION = auto()
    SYMPTOM = auto()
    ROOT_CAUSE = auto()
    ACTION = auto()
    OWNER = auto()
    TOOL_EVIDENCE = auto()
    POLICY_RULE = auto()
    VERIFICATION_OUTCOME = auto()


class GraphEdgeType(Enum):
    """01-domain-model §"KnowledgeGraph": 核心边类型."""

    MENTIONS = auto()
    SUPPORTED_BY = auto()
    RESOLVED_BY = auto()
    AFFECTS = auto()
    OWNED_BY = auto()
    SIMILAR_TO = auto()
    DERIVED_FROM = auto()
    CONFLICTS_WITH = auto()
    SUPERSEDES = auto()


class OutboxStatus(Enum):
    """08-transaction-and-outbox (deferred to SPEC-MK-003) §"Outbox Publisher": mirrors
    agent-runtime-service's own OutboxStatus exactly — introduced here already since
    "所有发布事件必须通过 Memory outbox" is a phase-00 mandatory constraint that this
    spec's publish/retention/ingest use cases already have to satisfy.
    """

    PENDING = auto()
    PUBLISHED = auto()
    DEAD_LETTER = auto()
