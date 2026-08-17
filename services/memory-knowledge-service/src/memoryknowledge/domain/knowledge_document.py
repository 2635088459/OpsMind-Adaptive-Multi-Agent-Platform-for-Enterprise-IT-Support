"""01-domain-model §"KnowledgeDocument" / §"DocumentChunk". 03-state-machine
§"Knowledge Document Ingestion 状态机" is the transition graph this module implements:

RECEIVED -> PARSED -> CHUNKED -> EMBEDDED -> INDEXED -> ACTIVE
RECEIVED / PARSED / CHUNKED / EMBEDDED -> FAILED
ACTIVE -> SUPERSEDED / EXPIRED / DELETED
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.enums import DocumentIngestionStatus
from memoryknowledge.domain.exceptions import InvalidDocumentIngestionTransitionException
from memoryknowledge.domain.ids import DocumentChunkId, KnowledgeDocumentId
from memoryknowledge.domain.values import EmbeddingRef

_FAILABLE = frozenset({
    DocumentIngestionStatus.RECEIVED, DocumentIngestionStatus.PARSED,
    DocumentIngestionStatus.CHUNKED, DocumentIngestionStatus.EMBEDDED,
})


@dataclass(frozen=True, slots=True)
class DocumentChunk:
    """02-business-invariants: "Document chunk 必须可追溯到 document version"; "reingestion
    不能原地修改旧 chunks，必须创建新 document version" — document_version pins each chunk to
    the KnowledgeDocument.version it was produced from.
    """

    chunk_id: DocumentChunkId
    document_id: KnowledgeDocumentId
    document_version: int
    chunk_index: int
    content: str
    token_count: int
    heading_path: str
    content_hash: str
    embedding_ref: EmbeddingRef | None = None

    def with_embedding(self, embedding_ref: EmbeddingRef) -> "DocumentChunk":
        return dataclasses.replace(self, embedding_ref=embedding_ref)


@dataclass(frozen=True, slots=True)
class KnowledgeDocument:
    document_id: KnowledgeDocumentId
    source_system: str
    external_id: str
    title: str
    document_type: str
    acl: tuple[str, ...]
    version: int
    ingestion_status: DocumentIngestionStatus
    content_hash: str
    created_at: datetime
    classification: str = "INTERNAL"
    """07-data-model `memory.knowledge_documents` §"classification text not null" —
    11-security §"检索前必须计算 access scope，并应用到：... Knowledge Document ACL" (ACL is
    checked separately by SearchMemoryService; classification is this field).
    """
    effective_from: datetime | None = None
    expires_at: datetime | None = None
    failure_reason: str | None = None

    @staticmethod
    def receive(
        document_id: KnowledgeDocumentId, source_system: str, external_id: str, title: str, document_type: str,
        acl: tuple[str, ...], version: int, content_hash: str, created_at: datetime, classification: str = "INTERNAL",
        effective_from: datetime | None = None, expires_at: datetime | None = None,
    ) -> "KnowledgeDocument":
        """02-business-invariants: "同一个 sourceSystem + externalId + version 只能
        ingestion 一次" — the uniqueness check itself lives at the repository (it needs to
        see other rows); this factory only produces the RECEIVED row.
        """
        return KnowledgeDocument(
            document_id=document_id, source_system=source_system, external_id=external_id, title=title,
            document_type=document_type, acl=acl, version=version, ingestion_status=DocumentIngestionStatus.RECEIVED,
            content_hash=content_hash, created_at=created_at, classification=classification,
            effective_from=effective_from, expires_at=expires_at,
        )

    def _require(self, expected: DocumentIngestionStatus) -> None:
        if self.ingestion_status is not expected:
            raise InvalidDocumentIngestionTransitionException(self.ingestion_status, frozenset({expected}))

    def mark_parsed(self) -> "KnowledgeDocument":
        """02-business-invariants: "PARSED 之前不能生成 chunk"."""
        self._require(DocumentIngestionStatus.RECEIVED)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.PARSED)

    def mark_chunked(self) -> "KnowledgeDocument":
        """02-business-invariants: "CHUNKED 后 chunks 不可原地修改"."""
        self._require(DocumentIngestionStatus.PARSED)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.CHUNKED)

    def mark_embedded(self) -> "KnowledgeDocument":
        self._require(DocumentIngestionStatus.CHUNKED)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.EMBEDDED)

    def mark_indexed(self) -> "KnowledgeDocument":
        self._require(DocumentIngestionStatus.EMBEDDED)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.INDEXED)

    def activate(self) -> "KnowledgeDocument":
        """02-business-invariants: "INDEXED -> ACTIVE 后才可被检索"."""
        self._require(DocumentIngestionStatus.INDEXED)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.ACTIVE)

    def mark_failed(self, reason: str) -> "KnowledgeDocument":
        """02-business-invariants: "EMBEDDED 可失败并重试" — extended to any pre-ACTIVE
        step, mirroring 10-failure-handling's "partial ingestion" concern (deferred to
        SPEC-MK-003+ for the retry mechanics themselves; this method only records the
        terminal-for-this-attempt outcome).
        """
        if self.ingestion_status not in _FAILABLE:
            raise InvalidDocumentIngestionTransitionException(self.ingestion_status, _FAILABLE)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.FAILED, failure_reason=reason)

    def supersede(self) -> "KnowledgeDocument":
        self._require(DocumentIngestionStatus.ACTIVE)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.SUPERSEDED)

    def expire(self) -> "KnowledgeDocument":
        self._require(DocumentIngestionStatus.ACTIVE)
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.EXPIRED)

    def delete(self) -> "KnowledgeDocument":
        return dataclasses.replace(self, ingestion_status=DocumentIngestionStatus.DELETED)
