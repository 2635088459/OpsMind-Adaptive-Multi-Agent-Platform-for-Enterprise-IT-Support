"""Domain events raised by the aggregates in memoryknowledge.domain.*. Frozen
dataclasses only — no framework dependency. These are the payload shapes
application/services/*.py fold into memoryknowledge.application.records.OutboxRecord
before appending to the outbox (SPEC-MK-001 domain-rules: "事件发布必须经过 Memory
outbox"); 06-event-contracts names the corresponding wire event_type for each.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.ids import (
    KnowledgeDocumentId,
    MemoryCandidateId,
    MemoryId,
    MemoryVersionId,
)


@dataclass(frozen=True, slots=True)
class MemoryCandidateCreated:
    """06-event-contracts: `memory.candidate.created.v1`."""

    candidate_id: MemoryCandidateId
    memory_type: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class MemoryCandidateRejected:
    """06-event-contracts: `memory.candidate.rejected.v1`."""

    candidate_id: MemoryCandidateId
    reason: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class MemoryPublished:
    """06-event-contracts: `memory.published.v1`."""

    memory_id: MemoryId
    memory_version_id: MemoryVersionId
    version: int
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class MemorySuperseded:
    """06-event-contracts: `memory.superseded.v1`."""

    memory_id: MemoryId
    superseded_version_id: MemoryVersionId
    superseding_version_id: MemoryVersionId
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class MemoryDeleted:
    """06-event-contracts: `memory.deleted.v1`. `source_type` distinguishes a Memory
    deletion from a KnowledgeDocument deletion sharing the same wire event.
    """

    source_type: str
    source_id: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class KnowledgeDocumentIndexed:
    """06-event-contracts: `knowledge.document.indexed.v1`."""

    document_id: KnowledgeDocumentId
    version: int
    chunk_count: int
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class KnowledgeGraphUpdated:
    """06-event-contracts: `knowledge.graph.updated.v1`. "用于 evaluation / observability,
    不用于驱动 Ticket 或 Workflow 状态."
    """

    graph_update_id: str
    source_type: str
    source_id: str
    node_count: int
    edge_count: int
    index_version: int
    occurred_at: datetime
