"""Domain value objects (identifiers). Pure stdlib, frozen and self-validating,
mirroring agent-runtime-service's own domain.ids convention and the Java sibling
services' record-per-value-object pattern.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass


def _require_uuid(value: uuid.UUID, field_name: str) -> None:
    if not isinstance(value, uuid.UUID):
        raise TypeError(f"{field_name} must be a uuid.UUID")


def _require_non_blank(value: str, field_name: str, max_length: int = 200) -> None:
    if not value or not value.strip():
        raise ValueError(f"{field_name} must not be blank")
    if len(value) > max_length:
        raise ValueError(f"{field_name} must be at most {max_length} characters")


@dataclass(frozen=True, slots=True)
class WorkingMemoryId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "workingMemoryId")

    @staticmethod
    def new_id() -> "WorkingMemoryId":
        return WorkingMemoryId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class MemoryId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "memoryId")

    @staticmethod
    def new_id() -> "MemoryId":
        return MemoryId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class MemoryVersionId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "memoryVersionId")

    @staticmethod
    def new_id() -> "MemoryVersionId":
        return MemoryVersionId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class MemoryCandidateId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "candidateId")

    @staticmethod
    def new_id() -> "MemoryCandidateId":
        return MemoryCandidateId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class KnowledgeDocumentId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "documentId")

    @staticmethod
    def new_id() -> "KnowledgeDocumentId":
        return KnowledgeDocumentId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class DocumentChunkId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "chunkId")

    @staticmethod
    def new_id() -> "DocumentChunkId":
        return DocumentChunkId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class GraphNodeId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "nodeId")

    @staticmethod
    def new_id() -> "GraphNodeId":
        return GraphNodeId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class GraphEdgeId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "edgeId")

    @staticmethod
    def new_id() -> "GraphEdgeId":
        return GraphEdgeId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class RetrievalId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "retrievalId")

    @staticmethod
    def new_id() -> "RetrievalId":
        return RetrievalId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TicketId:
    """Memory Knowledge's own reference to a Ticket Workflow aggregate.
    02-business-invariants §"状态所有权": Memory Knowledge may read/reference a ticket by
    id but must never use it to write Ticket lifecycle state directly.
    """

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "ticketId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TicketCycleId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "ticketCycleId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class WorkflowInstanceId:
    """Memory Knowledge's own reference to an Agent Runtime Workflow Instance.
    02-business-invariants §"状态所有权": read-only reference, never a write path.
    """

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "workflowInstanceId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class IdempotencyKey:
    """02-business-invariants (SPEC-MK-001 domain-rules): "需要写状态的命令必须具备幂等或
    版本保护" — carried by the commands whose durable write has no other natural
    uniqueness key (extract/publish/retention). WorkingMemory update instead uses
    optimistic version, and document ingestion instead uses the
    sourceSystem+externalId+version natural key — see each command's own docstring.
    """

    value: str

    def __post_init__(self) -> None:
        _require_non_blank(self.value, "idempotencyKey")

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class CorrelationId:
    """SPEC-MK-001 event-contract: every published/consumed event envelope must carry one."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "correlationId")

    @staticmethod
    def new_id() -> "CorrelationId":
        return CorrelationId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CausationId:
    """SPEC-MK-001 event-contract: every published/consumed event envelope must carry one."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "causationId")

    @staticmethod
    def new_id() -> "CausationId":
        return CausationId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)
