"""Application-layer exceptions — raised after I/O a pure domain function must not
perform (repository lookups, uniqueness checks, access-scope evaluation). Distinct from
memoryknowledge.domain.exceptions, which are raised from information a domain object
already carries. Error codes in each docstring match 05-api-contracts §"错误码" exactly.
"""

from __future__ import annotations

from memoryknowledge.domain.ids import GraphNodeId, KnowledgeDocumentId, MemoryCandidateId, MemoryId, WorkingMemoryId


class WorkingMemoryNotFoundException(RuntimeError):
    def __init__(self, working_memory_id: WorkingMemoryId) -> None:
        super().__init__(f"working memory {working_memory_id} not found")
        self.working_memory_id = working_memory_id


class MemoryCandidateNotFoundException(RuntimeError):
    def __init__(self, candidate_id: MemoryCandidateId) -> None:
        super().__init__(f"memory candidate {candidate_id} not found")
        self.candidate_id = candidate_id


class MemoryNotFoundException(RuntimeError):
    def __init__(self, memory_id: MemoryId) -> None:
        super().__init__(f"memory {memory_id} not found")
        self.memory_id = memory_id


class KnowledgeDocumentNotFoundException(RuntimeError):
    def __init__(self, document_id: KnowledgeDocumentId) -> None:
        super().__init__(f"knowledge document {document_id} not found")
        self.document_id = document_id


class GraphNodeNotFoundException(RuntimeError):
    def __init__(self, node_id: GraphNodeId) -> None:
        super().__init__(f"graph node {node_id} not found")
        self.node_id = node_id


class DocumentAlreadyIngestedException(RuntimeError):
    """05-api-contracts: `DOCUMENT_ALREADY_INGESTED`. 02-business-invariants: "同一个
    sourceSystem + externalId + version 只能 ingestion 一次."
    """

    def __init__(self, source_system: str, external_id: str, version: int) -> None:
        super().__init__(f"document {source_system}/{external_id} version {version} was already ingested")
        self.source_system = source_system
        self.external_id = external_id
        self.version = version


class DocumentIngestionFailedException(RuntimeError):
    """05-api-contracts: `DOCUMENT_INGESTION_FAILED`."""

    def __init__(self, reason: str) -> None:
        super().__init__(f"document ingestion failed: {reason}")
        self.reason = reason


class IdempotencyKeyReusedException(RuntimeError):
    """09-concurrency-and-idempotency (deferred detail to SPEC-MK-003): a different
    idempotency key already produced a durable result for this write.
    """

    def __init__(self, idempotency_key: str) -> None:
        super().__init__(f"idempotency key {idempotency_key} was already used for a different result")
        self.idempotency_key = idempotency_key


class RetrievalAccessDeniedException(RuntimeError):
    """05-api-contracts: `RETRIEVAL_ACCESS_DENIED`. 02-business-invariants: "检索必须应用
    tenant、role、classification 和 document ACL 过滤."
    """

    def __init__(self) -> None:
        super().__init__("the requester's access scope does not permit this retrieval")


class MemoryCandidateConflictingException(RuntimeError):
    """05-api-contracts: `MEMORY_CANDIDATE_CONFLICTING`. 02-business-invariants:
    "CONFLICTING candidate 必须人工或 policy 处理，不能自动覆盖 active memory."
    """

    def __init__(self, candidate_id: MemoryCandidateId, conflict_set_id: str | None) -> None:
        super().__init__(f"memory candidate {candidate_id} is CONFLICTING (conflict set {conflict_set_id}) and needs manual resolution")
        self.candidate_id = candidate_id
        self.conflict_set_id = conflict_set_id


class DeletionNotAuthorizedException(RuntimeError):
    """05-api-contracts: `DELETION_NOT_AUTHORIZED`."""

    def __init__(self, memory_id: MemoryId) -> None:
        super().__init__(f"deletion of memory {memory_id} is not authorized")
        self.memory_id = memory_id


class GraphTraversalDepthExceededException(RuntimeError):
    """02-business-invariants: "graph traversal depth MVP 默认不超过 2，除非 admin/research
    API 明确提升."
    """

    def __init__(self, requested_depth: int, max_allowed: int) -> None:
        super().__init__(f"requested graph traversal depth {requested_depth} exceeds the allowed maximum {max_allowed}")
        self.requested_depth = requested_depth
        self.max_allowed = max_allowed


class OptimisticConcurrencyConflictException(RuntimeError):
    """Raised by an in-memory/Postgres repository's compare-and-swap save() when the
    stored row's status (MemoryCandidate/MemoryVersion/KnowledgeDocument — none of which
    carry a separate version counter, per 01-domain-model's own field lists) has moved
    on since the caller last read it. Mirrors WorkingMemoryVersionConflictException's
    role for the one aggregate (WorkingMemory) that does carry an explicit version.
    """

    def __init__(self, entity_name: str, entity_id: str, expected_status: str | None, actual_status: str | None) -> None:
        super().__init__(f"{entity_name} {entity_id} expected status {expected_status} but found {actual_status}")
        self.entity_name = entity_name
        self.entity_id = entity_id
        self.expected_status = expected_status
        self.actual_status = actual_status


class WorkingMemoryScopeConflictException(RuntimeError):
    """01-domain-model: "同一个 scope 只能有一个 active WorkingMemory" — raised when a create
    (expected_version == 0) races another create for the same
    ticket_id/ticket_cycle_id/workflow_instance_id scope.
    """

    def __init__(self) -> None:
        super().__init__("an active working memory already exists for this scope")
