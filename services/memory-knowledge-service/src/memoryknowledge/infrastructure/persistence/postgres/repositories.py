"""SPEC-MK-002: SQLAlchemy/Postgres-backed implementations of every
memoryknowledge.application.ports_out repository Protocol. Each repository opens one
short-lived Session per call (`with self._session_factory() as session:`) — real
cross-repository transaction boundaries land with a later spec once a use case needs
to coordinate more than one aggregate write per request; every individual write here
is already atomic and safe under concurrent access (see each save()'s docstring).

CAS pattern: WorkingMemory carries its own explicit `version` int (matches
agent-runtime-service's own WorkflowInstanceRepository CAS exactly — Core
update()/insert() bound to a real WHERE clause, rowcount checked, never SQLAlchemy
ORM's session.get()-then-mutate-then-commit, which generates no version predicate at
all). MemoryCandidate/MemoryVersion/KnowledgeDocument carry no version field
(01-domain-model's own field lists) — those use a status-based compare-and-swap via
`save(entity, expected_status)` instead, matching
infrastructure.persistence.in_memory's own in-process equivalent exactly.
"""

from __future__ import annotations

import dataclasses
import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from memoryknowledge.application.exceptions import OptimisticConcurrencyConflictException, WorkingMemoryScopeConflictException
from memoryknowledge.application.records import AuditRecordEntry, CommandIdempotencyRecord, OutboxRecord
from memoryknowledge.domain.enums import (
    DocumentIngestionStatus,
    GraphEdgeType,
    GraphNodeStatus,
    GraphNodeType,
    MemoryCandidateStatus,
    MemoryType,
    MemoryVersionStatus,
    OutboxStatus,
    WorkingMemoryStatus,
)
from memoryknowledge.domain.exceptions import WorkingMemoryVersionConflictException
from memoryknowledge.domain.ids import (
    CausationId,
    CorrelationId,
    DocumentChunkId,
    GraphEdgeId,
    GraphNodeId,
    IdempotencyKey,
    KnowledgeDocumentId,
    MemoryCandidateId,
    MemoryId,
    MemoryVersionId,
    RetrievalId,
    TicketCycleId,
    TicketId,
    WorkflowInstanceId,
    WorkingMemoryId,
)
from memoryknowledge.domain.knowledge_document import DocumentChunk, KnowledgeDocument
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.retrieval import RetrievalLog
from memoryknowledge.domain.values import EmbeddingRef, GraphPath, RedactionReport, SourceRef
from memoryknowledge.domain.working_memory import RejectedHypothesis, ToolEvidenceRef, WorkingMemory
from memoryknowledge.infrastructure.persistence.postgres.models import (
    AuditEventRow,
    CommandIdempotencyRow,
    DocumentChunkRow,
    EmbeddingRow,
    GraphEdgeRow,
    GraphNodeRow,
    KnowledgeDocumentRow,
    MemoryCandidateRow,
    MemoryRow,
    MemoryVersionRow,
    OutboxEventRow,
    ProcessedEventRow,
    RetrievalLogRow,
    WorkingMemoryRow,
)

# --------------------------------------------------------------------------------
# Value-object <-> JSONB conversion helpers. Every one of these round-trips through
# dataclasses.asdict()/**kwargs reconstruction — safe because every value object here
# (SourceRef, RedactionReport, EmbeddingRef, GraphPath, RejectedHypothesis,
# ToolEvidenceRef) is a flat, JSON-safe dataclass (str/int/float/bool/None fields, or
# in GraphPath's case, tuples of str that need re-wrapping after a JSONB round trip
# turns them into plain lists).
# --------------------------------------------------------------------------------


def _source_refs_to_json(source_refs: tuple[SourceRef, ...]) -> list[dict]:
    return [dataclasses.asdict(r) for r in source_refs]


def _json_to_source_refs(data: list[dict]) -> tuple[SourceRef, ...]:
    return tuple(SourceRef(**d) for d in data)


def _redaction_report_to_json(report: RedactionReport | None) -> dict | None:
    return dataclasses.asdict(report) if report is not None else None


def _json_to_redaction_report(data: dict | None) -> RedactionReport | None:
    if data is None:
        return None
    return RedactionReport(
        redacted_fields=tuple(data.get("redacted_fields", [])),
        secret_patterns_matched=tuple(data.get("secret_patterns_matched", [])),
        policy_rule_ids=tuple(data.get("policy_rule_ids", [])),
    )


def _embedding_ref_to_json(ref: EmbeddingRef | None) -> dict | None:
    return dataclasses.asdict(ref) if ref is not None else None


def _json_to_embedding_ref(data: dict | None) -> EmbeddingRef | None:
    return EmbeddingRef(**data) if data is not None else None


def _float(value) -> float:
    return float(value) if isinstance(value, Decimal) else value


def _current_status(session: Session, model, entity_id, status_column: str) -> str | None:
    row = session.get(model, entity_id)
    return getattr(row, status_column) if row is not None else None


# --------------------------------------------------------------------------------
# WorkingMemory
# --------------------------------------------------------------------------------


def _working_memory_to_row_values(working_memory: WorkingMemory, created_at: datetime) -> dict:
    return dict(
        id=working_memory.working_memory_id.value, ticket_id=working_memory.ticket_id.value,
        ticket_cycle_id=working_memory.ticket_cycle_id.value, workflow_instance_id=working_memory.workflow_instance_id.value,
        version=working_memory.version, status=working_memory.status.name, facts_json=list(working_memory.facts),
        hypotheses_json=list(working_memory.hypotheses),
        rejected_hypotheses_json=[
            {"hypothesis": r.hypothesis, "reason": r.reason, "rejected_at": r.rejected_at.isoformat()}
            for r in working_memory.rejected_hypotheses
        ],
        completed_tasks_json=list(working_memory.completed_tasks), pending_tasks_json=list(working_memory.pending_tasks),
        tool_evidence_refs_json=[dataclasses.asdict(t) for t in working_memory.tool_evidence_refs],
        approval_decision_refs_json=list(working_memory.approval_decision_refs),
        context_summary=working_memory.context_summary, updated_by=working_memory.updated_by,
        created_at=created_at, updated_at=working_memory.updated_at,
    )


def _row_to_working_memory(row: WorkingMemoryRow) -> WorkingMemory:
    return WorkingMemory(
        working_memory_id=WorkingMemoryId(row.id), ticket_id=TicketId(row.ticket_id), ticket_cycle_id=TicketCycleId(row.ticket_cycle_id),
        workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id), version=row.version,
        status=WorkingMemoryStatus[row.status],
        facts=tuple(row.facts_json), hypotheses=tuple(row.hypotheses_json),
        rejected_hypotheses=tuple(
            RejectedHypothesis(r["hypothesis"], r["reason"], datetime.fromisoformat(r["rejected_at"]))
            for r in row.rejected_hypotheses_json
        ),
        completed_tasks=tuple(row.completed_tasks_json), pending_tasks=tuple(row.pending_tasks_json),
        tool_evidence_refs=tuple(ToolEvidenceRef(**t) for t in row.tool_evidence_refs_json),
        approval_decision_refs=tuple(row.approval_decision_refs_json), context_summary=row.context_summary,
        updated_by=row.updated_by, updated_at=row.updated_at,
    )


class PostgresWorkingMemoryRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, working_memory_id: WorkingMemoryId) -> WorkingMemory | None:
        with self._session_factory() as session:
            row = session.get(WorkingMemoryRow, working_memory_id.value)
            return _row_to_working_memory(row) if row else None

    def find_active_by_scope(
        self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_instance_id: WorkflowInstanceId
    ) -> WorkingMemory | None:

        with self._session_factory() as session:
            stmt = select(WorkingMemoryRow).where(
                WorkingMemoryRow.ticket_id == ticket_id.value, WorkingMemoryRow.ticket_cycle_id == ticket_cycle_id.value,
                WorkingMemoryRow.workflow_instance_id == workflow_instance_id.value, WorkingMemoryRow.status == "ACTIVE",
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_working_memory(row) if row else None

    def save(self, working_memory: WorkingMemory) -> WorkingMemory:
        """01-domain-model: "更新必须使用 optimistic version." A single atomic
        `UPDATE ... WHERE id = :id AND version = :expected_previous_version` — see this
        module's own docstring for why the ORM's session.get()-then-mutate pattern
        cannot be trusted for this.
        """
        with self._session_factory() as session:
            existing = session.get(WorkingMemoryRow, working_memory.working_memory_id.value)
            if existing is None:
                try:
                    session.execute(
                        WorkingMemoryRow.__table__.insert().values(
                            **_working_memory_to_row_values(working_memory, created_at=working_memory.updated_at)
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    # 02-business-invariants: "同一个 scope 只能有一个 active WorkingMemory"
                    # — uq_working_memory_active_scope fired.
                    raise WorkingMemoryScopeConflictException() from exc
            else:
                expected_previous_version = working_memory.version - 1
                result = session.execute(
                    update(WorkingMemoryRow.__table__)
                    .where(WorkingMemoryRow.id == working_memory.working_memory_id.value, WorkingMemoryRow.version == expected_previous_version)
                    .values(**{k: v for k, v in _working_memory_to_row_values(working_memory, created_at=existing.created_at).items() if k != "id"})
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise WorkingMemoryVersionConflictException(expected_previous_version, existing.version)
                session.commit()
            return working_memory


# --------------------------------------------------------------------------------
# MemoryCandidate
# --------------------------------------------------------------------------------


def _row_to_memory_candidate(row: MemoryCandidateRow) -> MemoryCandidate:
    return MemoryCandidate(
        candidate_id=MemoryCandidateId(row.id), memory_type=MemoryType[row.memory_type], status=MemoryCandidateStatus[row.status],
        source_refs=_json_to_source_refs(row.source_refs_json), candidate_text=row.candidate_text, source_hash=row.source_hash,
        redacted_text=row.redacted_text, redaction_report=_json_to_redaction_report(row.redaction_report_json),
        confidence_score=_float(row.confidence_score), usefulness_score=_float(row.usefulness_score),
        duplicate_of_memory_id=MemoryId(row.duplicate_of_memory_id) if row.duplicate_of_memory_id else None,
        conflict_set_id=row.conflict_set_id, review_required=row.review_required, rejection_reason=row.rejection_reason,
        created_at=row.created_at,
    )


class PostgresMemoryCandidateRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, candidate_id: MemoryCandidateId) -> MemoryCandidate | None:
        with self._session_factory() as session:
            row = session.get(MemoryCandidateRow, candidate_id.value)
            return _row_to_memory_candidate(row) if row else None

    def find_by_source_hash(self, source_hash: str, memory_type: MemoryType) -> MemoryCandidate | None:
        with self._session_factory() as session:
            stmt = select(MemoryCandidateRow).where(
                MemoryCandidateRow.source_hash == source_hash, MemoryCandidateRow.memory_type == memory_type.name,
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_memory_candidate(row) if row else None

    def save(self, candidate: MemoryCandidate, expected_status: MemoryCandidateStatus | None) -> MemoryCandidate:
        values = dict(
            memory_type=candidate.memory_type.name, status=candidate.status.name, source_refs_json=_source_refs_to_json(candidate.source_refs),
            candidate_text=candidate.candidate_text, source_hash=candidate.source_hash, redacted_text=candidate.redacted_text,
            redaction_report_json=_redaction_report_to_json(candidate.redaction_report),
            confidence_score=candidate.confidence_score, usefulness_score=candidate.usefulness_score,
            duplicate_of_memory_id=candidate.duplicate_of_memory_id.value if candidate.duplicate_of_memory_id else None,
            conflict_set_id=candidate.conflict_set_id, review_required=candidate.review_required,
            rejection_reason=candidate.rejection_reason,
        )
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(
                        MemoryCandidateRow.__table__.insert().values(
                            id=candidate.candidate_id.value, created_at=candidate.created_at, **values
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException("memory_candidate", str(candidate.candidate_id), None, "already exists") from exc
            else:
                result = session.execute(
                    update(MemoryCandidateRow.__table__)
                    .where(MemoryCandidateRow.id == candidate.candidate_id.value, MemoryCandidateRow.status == expected_status.name)
                    .values(**values)
                )
                if result.rowcount != 1:
                    actual = _current_status(session, MemoryCandidateRow, candidate.candidate_id.value, "status")
                    session.rollback()
                    raise OptimisticConcurrencyConflictException("memory_candidate", str(candidate.candidate_id), expected_status.name, actual)
                session.commit()
            return candidate


# --------------------------------------------------------------------------------
# Memory / MemoryVersion
# --------------------------------------------------------------------------------


def _row_to_memory_version(row: MemoryVersionRow) -> MemoryVersion:
    return MemoryVersion(
        memory_version_id=MemoryVersionId(row.id), memory_id=MemoryId(row.memory_id), version=row.version,
        status=MemoryVersionStatus[row.status], content=row.content, summary=row.summary or "",
        source_refs=_json_to_source_refs(row.source_refs_json), redaction_report=_json_to_redaction_report(row.redaction_report_json) or RedactionReport(),
        confidence_score=_float(row.confidence_score), source_trust_score=_float(row.source_trust_score),
        embedding_ref=_json_to_embedding_ref(row.embedding_ref_json), source_hash=row.source_hash,
        supersedes_version_id=MemoryVersionId(row.supersedes_version_id) if row.supersedes_version_id else None,
        created_by=row.created_by, created_at=row.created_at,
    )


class PostgresMemoryRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_memory_by_id(self, memory_id: MemoryId) -> Memory | None:
        with self._session_factory() as session:
            row = session.get(MemoryRow, memory_id.value)
            return (
                Memory(memory_id=MemoryId(row.id), memory_type=MemoryType[row.memory_type], classification=row.classification, created_at=row.created_at)
                if row else None
            )

    def save_memory(self, memory: Memory) -> Memory:
        """Idempotent insert — a Memory's own fields never change after creation."""
        with self._session_factory() as session:
            existing = session.get(MemoryRow, memory.memory_id.value)
            if existing is None:
                session.execute(
                    MemoryRow.__table__.insert().values(
                        id=memory.memory_id.value, memory_type=memory.memory_type.name, classification=memory.classification,
                        created_at=memory.created_at, updated_at=memory.created_at,
                    )
                )
                session.commit()
            return memory

    def find_active_version(self, memory_id: MemoryId) -> MemoryVersion | None:

        with self._session_factory() as session:
            stmt = select(MemoryVersionRow).where(MemoryVersionRow.memory_id == memory_id.value, MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.name)
            row = session.execute(stmt).scalars().first()
            return _row_to_memory_version(row) if row else None

    def find_version_by_id(self, memory_version_id: MemoryVersionId) -> MemoryVersion | None:
        with self._session_factory() as session:
            row = session.get(MemoryVersionRow, memory_version_id.value)
            return _row_to_memory_version(row) if row else None

    def find_versions(self, memory_id: MemoryId) -> list[MemoryVersion]:

        with self._session_factory() as session:
            stmt = select(MemoryVersionRow).where(MemoryVersionRow.memory_id == memory_id.value).order_by(MemoryVersionRow.version)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_memory_version(row) for row in rows]

    def save_version(self, version: MemoryVersion, expected_status: MemoryVersionStatus | None) -> MemoryVersion:
        values = dict(
            status=version.status.name, content=version.content, summary=version.summary,
            source_refs_json=_source_refs_to_json(version.source_refs), redaction_report_json=_redaction_report_to_json(version.redaction_report),
            confidence_score=version.confidence_score, source_trust_score=version.source_trust_score,
            embedding_ref_json=_embedding_ref_to_json(version.embedding_ref), source_hash=version.source_hash,
            supersedes_version_id=version.supersedes_version_id.value if version.supersedes_version_id else None,
        )
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(
                        MemoryVersionRow.__table__.insert().values(
                            id=version.memory_version_id.value, memory_id=version.memory_id.value, version=version.version,
                            created_by=version.created_by, created_at=version.created_at, **values,
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException("memory_version", str(version.memory_version_id), None, "already exists") from exc
            else:
                result = session.execute(
                    update(MemoryVersionRow.__table__)
                    .where(MemoryVersionRow.id == version.memory_version_id.value, MemoryVersionRow.status == expected_status.name)
                    .values(**values)
                )
                if result.rowcount != 1:
                    actual = _current_status(session, MemoryVersionRow, version.memory_version_id.value, "status")
                    session.rollback()
                    raise OptimisticConcurrencyConflictException("memory_version", str(version.memory_version_id), expected_status.name, actual)
                session.commit()

            # Denormalized bookkeeping pointer — see MemoryRow.current_version_id's own
            # docstring for why this is safe to keep outside the CAS above.
            if version.status is MemoryVersionStatus.ACTIVE:
                session.execute(
                    update(MemoryRow.__table__).where(MemoryRow.id == version.memory_id.value).values(
                        current_version_id=version.memory_version_id.value, updated_at=version.created_at,
                    )
                )
                session.commit()
            return version

    def find_active_versions_by_type(self, memory_type_names: tuple[str, ...], limit: int) -> list[MemoryVersion]:

        with self._session_factory() as session:
            stmt = select(MemoryVersionRow).join(MemoryRow, MemoryVersionRow.memory_id == MemoryRow.id).where(
                MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.name
            )
            if memory_type_names:
                stmt = stmt.where(MemoryRow.memory_type.in_(memory_type_names))
            stmt = stmt.limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_memory_version(row) for row in rows]

    def find_by_source_hash(self, source_hash: str) -> MemoryVersion | None:

        with self._session_factory() as session:
            stmt = select(MemoryVersionRow).where(MemoryVersionRow.source_hash == source_hash, MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.name)
            row = session.execute(stmt).scalars().first()
            return _row_to_memory_version(row) if row else None


# --------------------------------------------------------------------------------
# KnowledgeDocument / DocumentChunk
# --------------------------------------------------------------------------------


def _row_to_knowledge_document(row: KnowledgeDocumentRow) -> KnowledgeDocument:
    return KnowledgeDocument(
        document_id=KnowledgeDocumentId(row.id), source_system=row.source_system, external_id=row.external_id, title=row.title,
        document_type=row.document_type, acl=tuple(row.acl_json), version=row.version, ingestion_status=DocumentIngestionStatus[row.status],
        content_hash=row.content_hash, created_at=row.created_at, classification=row.classification,
        effective_from=row.effective_from, expires_at=row.expires_at, failure_reason=row.failure_reason,
    )


def _row_to_document_chunk(row: DocumentChunkRow) -> DocumentChunk:
    return DocumentChunk(
        chunk_id=DocumentChunkId(row.id), document_id=KnowledgeDocumentId(row.document_id), document_version=row.document_version,
        chunk_index=row.chunk_index, content=row.content, token_count=row.token_count, heading_path=row.heading_path or "",
        content_hash=row.content_hash, embedding_ref=_json_to_embedding_ref(row.embedding_ref_json),
    )


class PostgresKnowledgeDocumentRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, document_id: KnowledgeDocumentId) -> KnowledgeDocument | None:
        with self._session_factory() as session:
            row = session.get(KnowledgeDocumentRow, document_id.value)
            return _row_to_knowledge_document(row) if row else None

    def find_by_natural_key(self, source_system: str, external_id: str, version: int) -> KnowledgeDocument | None:

        with self._session_factory() as session:
            stmt = select(KnowledgeDocumentRow).where(
                KnowledgeDocumentRow.source_system == source_system, KnowledgeDocumentRow.external_id == external_id,
                KnowledgeDocumentRow.version == version,
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_knowledge_document(row) if row else None

    def save(self, document: KnowledgeDocument, expected_status: str | None) -> KnowledgeDocument:
        values = dict(
            title=document.title, document_type=document.document_type, acl_json=list(document.acl), status=document.ingestion_status.name,
            classification=document.classification, content_hash=document.content_hash, effective_from=document.effective_from,
            expires_at=document.expires_at, failure_reason=document.failure_reason, updated_at=document.created_at,
        )
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(
                        KnowledgeDocumentRow.__table__.insert().values(
                            id=document.document_id.value, source_system=document.source_system, external_id=document.external_id,
                            version=document.version, created_at=document.created_at, **values,
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException("knowledge_document", str(document.document_id), None, "already exists") from exc
            else:
                result = session.execute(
                    update(KnowledgeDocumentRow.__table__)
                    .where(KnowledgeDocumentRow.id == document.document_id.value, KnowledgeDocumentRow.status == expected_status)
                    .values(**values)
                )
                if result.rowcount != 1:
                    actual = _current_status(session, KnowledgeDocumentRow, document.document_id.value, "status")
                    session.rollback()
                    raise OptimisticConcurrencyConflictException("knowledge_document", str(document.document_id), expected_status, actual)
                session.commit()
            return document

    def save_chunks(self, chunks: tuple[DocumentChunk, ...]) -> None:
        """02-business-invariants: "CHUNKED 后 chunks 不可原地修改" — always an insert of
        newly-created chunks, never an update.
        """
        if not chunks:
            return
        with self._session_factory() as session:
            session.execute(
                DocumentChunkRow.__table__.insert(),
                [
                    dict(
                        id=chunk.chunk_id.value, document_id=chunk.document_id.value, document_version=chunk.document_version,
                        chunk_index=chunk.chunk_index, content=chunk.content, content_hash=chunk.content_hash,
                        heading_path=chunk.heading_path, token_count=chunk.token_count,
                        embedding_ref_json=_embedding_ref_to_json(chunk.embedding_ref),
                    )
                    for chunk in chunks
                ],
            )
            session.commit()

    def find_chunks(self, document_id: KnowledgeDocumentId) -> list[DocumentChunk]:

        with self._session_factory() as session:
            stmt = select(DocumentChunkRow).where(DocumentChunkRow.document_id == document_id.value).order_by(DocumentChunkRow.chunk_index)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_document_chunk(row) for row in rows]

    def find_active_chunks(self, limit: int) -> list[DocumentChunk]:

        with self._session_factory() as session:
            stmt = (
                select(DocumentChunkRow)
                .join(KnowledgeDocumentRow, DocumentChunkRow.document_id == KnowledgeDocumentRow.id)
                .where(KnowledgeDocumentRow.status == DocumentIngestionStatus.ACTIVE.name)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_row_to_document_chunk(row) for row in rows]


# --------------------------------------------------------------------------------
# Embedding
# --------------------------------------------------------------------------------


class PostgresEmbeddingRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, embedding_ref: EmbeddingRef, vector: tuple[float, ...]) -> None:
        with self._session_factory() as session:
            existing = session.get(EmbeddingRow, embedding_ref.vector_id)
            if existing is not None:
                return
            session.execute(
                EmbeddingRow.__table__.insert().values(
                    vector_id=embedding_ref.vector_id, provider=embedding_ref.provider, model=embedding_ref.model,
                    dimensions=embedding_ref.dimensions, embedding=list(vector), created_at=_utcnow(),
                )
            )
            session.commit()

    def find(self, vector_id: str) -> tuple[float, ...] | None:
        with self._session_factory() as session:
            row = session.get(EmbeddingRow, vector_id)
            return tuple(float(v) for v in row.embedding) if row is not None else None


def _utcnow() -> datetime:
    from datetime import UTC

    return datetime.now(UTC)


# --------------------------------------------------------------------------------
# RetrievalLog
# --------------------------------------------------------------------------------


class PostgresRetrievalLogRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, log: RetrievalLog) -> None:
        with self._session_factory() as session:
            session.execute(
                RetrievalLogRow.__table__.insert().values(
                    id=log.retrieval_id.value, requester_type=log.requester_type, requester_id=log.requester_id,
                    ticket_id=log.ticket_id.value if log.ticket_id else None,
                    ticket_cycle_id=log.ticket_cycle_id.value if log.ticket_cycle_id else None,
                    workflow_instance_id=log.workflow_instance_id.value if log.workflow_instance_id else None,
                    query_hash=log.query_hash, filters_json=dict(log.filters), result_refs_json=list(log.result_refs),
                    graph_paths_json=[dataclasses.asdict(p) for p in log.graph_paths], degraded=log.degraded,
                    latency_ms=log.latency_ms, created_at=log.created_at,
                )
            )
            session.commit()

    def find_recent(self, limit: int) -> list[RetrievalLog]:

        with self._session_factory() as session:
            stmt = select(RetrievalLogRow).order_by(RetrievalLogRow.created_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [
                RetrievalLog(
                    retrieval_id=RetrievalId(row.id), requester_type=row.requester_type, requester_id=row.requester_id,
                    ticket_id=TicketId(row.ticket_id) if row.ticket_id else None,
                    ticket_cycle_id=TicketCycleId(row.ticket_cycle_id) if row.ticket_cycle_id else None,
                    workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id) if row.workflow_instance_id else None,
                    query_hash=row.query_hash, filters=dict(row.filters_json), result_refs=tuple(row.result_refs_json),
                    graph_paths=tuple(
                        GraphPath(tuple(p["node_ids"]), tuple(p["edge_ids"]), p["path_score"], p["explanation"]) for p in row.graph_paths_json
                    ),
                    degraded=row.degraded, latency_ms=row.latency_ms, created_at=row.created_at,
                )
                for row in rows
            ]


# --------------------------------------------------------------------------------
# GraphNode / GraphEdge
# --------------------------------------------------------------------------------


def _row_to_graph_node(row: GraphNodeRow) -> GraphNode:
    return GraphNode(
        node_id=GraphNodeId(row.id), node_type=GraphNodeType[row.node_type], stable_key=row.stable_key, display_name=row.display_name,
        properties=dict(row.properties_json), classification=row.classification, source_refs=_json_to_source_refs(row.source_refs_json),
        status=GraphNodeStatus[row.status], created_at=row.created_at,
    )


class PostgresGraphNodeRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, node_id: GraphNodeId) -> GraphNode | None:
        with self._session_factory() as session:
            row = session.get(GraphNodeRow, node_id.value)
            return _row_to_graph_node(row) if row else None

    def find_by_stable_key(self, stable_key: str, node_type: GraphNodeType) -> GraphNode | None:

        with self._session_factory() as session:
            stmt = select(GraphNodeRow).where(GraphNodeRow.stable_key == stable_key, GraphNodeRow.node_type == node_type.name)
            row = session.execute(stmt).scalars().first()
            return _row_to_graph_node(row) if row else None

    def save(self, node: GraphNode) -> GraphNode:
        values = dict(
            node_type=node.node_type.name, stable_key=node.stable_key, display_name=node.display_name, properties_json=dict(node.properties),
            classification=node.classification, source_refs_json=_source_refs_to_json(node.source_refs), status=node.status.name,
            updated_at=node.created_at,
        )
        with self._session_factory() as session:
            existing = session.get(GraphNodeRow, node.node_id.value)
            if existing is None:
                session.execute(GraphNodeRow.__table__.insert().values(id=node.node_id.value, created_at=node.created_at, **values))
            else:
                session.execute(update(GraphNodeRow.__table__).where(GraphNodeRow.id == node.node_id.value).values(**values))
            session.commit()
            return node

    def find_by_ids(self, node_ids: tuple[GraphNodeId, ...]) -> list[GraphNode]:

        if not node_ids:
            return []
        with self._session_factory() as session:
            stmt = select(GraphNodeRow).where(GraphNodeRow.id.in_([n.value for n in node_ids]))
            rows = session.execute(stmt).scalars().all()
            return [_row_to_graph_node(row) for row in rows]


def _row_to_graph_edge(row: GraphEdgeRow) -> GraphEdge:
    return GraphEdge(
        edge_id=GraphEdgeId(row.id), edge_type=GraphEdgeType[row.edge_type],
        from_node_id=GraphNodeId(row.from_node_id), to_node_id=GraphNodeId(row.to_node_id), confidence=_float(row.confidence),
        evidence_refs=_json_to_source_refs(row.evidence_refs_json), properties=dict(row.properties_json), source_hash=row.source_hash,
        status=GraphNodeStatus[row.status], created_at=row.created_at,
    )


class PostgresGraphEdgeRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, edge_id: GraphEdgeId) -> GraphEdge | None:
        with self._session_factory() as session:
            row = session.get(GraphEdgeRow, edge_id.value)
            return _row_to_graph_edge(row) if row else None

    def find_by_natural_key(self, from_node_id: GraphNodeId, to_node_id: GraphNodeId, edge_type: str, source_hash: str) -> GraphEdge | None:

        with self._session_factory() as session:
            stmt = select(GraphEdgeRow).where(
                GraphEdgeRow.from_node_id == from_node_id.value, GraphEdgeRow.to_node_id == to_node_id.value,
                GraphEdgeRow.edge_type == edge_type, GraphEdgeRow.source_hash == source_hash,
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_graph_edge(row) if row else None

    def save(self, edge: GraphEdge) -> GraphEdge:
        values = dict(
            edge_type=edge.edge_type.name, confidence=edge.confidence, evidence_refs_json=_source_refs_to_json(edge.evidence_refs),
            source_hash=edge.source_hash, properties_json=dict(edge.properties), status=edge.status.name, updated_at=edge.created_at,
        )
        with self._session_factory() as session:
            existing = session.get(GraphEdgeRow, edge.edge_id.value)
            if existing is None:
                session.execute(
                    GraphEdgeRow.__table__.insert().values(
                        id=edge.edge_id.value, from_node_id=edge.from_node_id.value, to_node_id=edge.to_node_id.value,
                        created_at=edge.created_at, **values,
                    )
                )
            else:
                session.execute(update(GraphEdgeRow.__table__).where(GraphEdgeRow.id == edge.edge_id.value).values(**values))
            session.commit()
            return edge

    def find_adjacent(self, node_id: GraphNodeId, limit: int) -> list[GraphEdge]:

        with self._session_factory() as session:
            stmt = (
                select(GraphEdgeRow)
                .where(
                    GraphEdgeRow.status == GraphNodeStatus.VISIBLE.name,
                    or_(GraphEdgeRow.from_node_id == node_id.value, GraphEdgeRow.to_node_id == node_id.value),
                )
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_row_to_graph_edge(row) for row in rows]


# --------------------------------------------------------------------------------
# ProcessedEvent / Outbox / CommandIdempotency
# --------------------------------------------------------------------------------


class PostgresProcessedEventRepository:
    """SPEC-MK-001 domain-rules: "所有消费事件必须 processed-event 去重" — dedup keyed by
    (event_id, consumer_name), mirroring agent-runtime-service's own
    PostgresProcessedEventRepository exactly.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        with self._session_factory() as session:
            row = session.get(ProcessedEventRow, (event_id, consumer_name))
            return row is not None

    def mark_processed(self, event_id: str, consumer_name: str, processed_at: datetime, event_type: str | None = None) -> None:
        with self._session_factory() as session:
            existing = session.get(ProcessedEventRow, (event_id, consumer_name))
            if existing is not None:
                return
            session.add(ProcessedEventRow(event_id=event_id, consumer_name=consumer_name, event_type=event_type, processed_at=processed_at))
            try:
                session.commit()
            except IntegrityError:
                # Two workers racing to mark the same (event_id, consumer_name): whichever
                # loses is a no-op, not an error.
                session.rollback()


def _to_outbox_record(row: OutboxEventRow) -> OutboxRecord:
    return OutboxRecord(
        outbox_id=row.id, event_type=row.event_type, schema_version=row.schema_version, aggregate_id=row.aggregate_id,
        payload=row.payload_json, occurred_at=row.created_at, correlation_id=CorrelationId(row.correlation_id),
        causation_id=CausationId(row.causation_id), status=OutboxStatus[row.status], attempts=row.attempts,
        available_at=row.available_at, published_at=row.published_at, ticket_id=str(row.ticket_id) if row.ticket_id else None,
    )


class PostgresOutboxRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, record: OutboxRecord) -> None:
        with self._session_factory() as session:
            session.execute(
                OutboxEventRow.__table__.insert().values(
                    id=record.outbox_id, event_type=record.event_type, schema_version=record.schema_version,
                    aggregate_id=record.aggregate_id, ticket_id=uuid.UUID(record.ticket_id) if record.ticket_id else None,
                    payload_json=record.payload, correlation_id=record.correlation_id.value, causation_id=record.causation_id.value,
                    status=OutboxStatus.PENDING.name, attempts=0, available_at=record.available_at or record.occurred_at,
                    published_at=None, created_at=record.occurred_at,
                )
            )
            session.commit()

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:

        with self._session_factory() as session:
            stmt = (
                select(OutboxEventRow)
                .where(OutboxEventRow.status == OutboxStatus.PENDING.name, OutboxEventRow.available_at <= now)
                .order_by(OutboxEventRow.available_at)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_outbox_record(row) for row in rows]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(status=OutboxStatus.PUBLISHED.name, published_at=published_at)
            )
            session.commit()

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(attempts=attempts, available_at=next_available_at)
            )
            session.commit()

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        with self._session_factory() as session:
            session.execute(update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(status=OutboxStatus.DEAD_LETTER.name))
            session.commit()

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:

        with self._session_factory() as session:
            stmt = select(OutboxEventRow).where(OutboxEventRow.status == OutboxStatus.DEAD_LETTER.name).order_by(OutboxEventRow.created_at).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_to_outbox_record(row) for row in rows]

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(
                    status=OutboxStatus.PENDING.name, attempts=0, available_at=available_at, published_at=None,
                )
            )
            session.commit()


class PostgresCommandIdempotencyRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None:
        with self._session_factory() as session:
            row = session.get(CommandIdempotencyRow, str(idempotency_key))
            if row is None:
                return None
            return CommandIdempotencyRecord(
                idempotency_key=IdempotencyKey(row.idempotency_key), command_type=row.command_type, target_id=row.target_id,
                request_hash=row.request_hash, response_json=row.response_json, created_at=row.created_at, expires_at=row.expires_at,
            )

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord:
        with self._session_factory() as session:
            try:
                session.execute(
                    CommandIdempotencyRow.__table__.insert().values(
                        idempotency_key=str(record.idempotency_key), command_type=record.command_type, target_id=record.target_id,
                        request_hash=record.request_hash, response_json=record.response_json, created_at=record.created_at,
                        expires_at=record.expires_at,
                    )
                )
                session.commit()
            except IntegrityError:
                # A concurrent caller already inserted this exact key first — whoever loses
                # this race must not overwrite the winner's stored result.
                session.rollback()
            return record


class PostgresAuditRecordRepository:
    """SPEC-MK-003 12-observability §"Audit Events". Append-only, mirroring
    PostgresOutboxRepository's own insert-only append() shape.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, entry: AuditRecordEntry) -> None:
        with self._session_factory() as session:
            session.execute(
                AuditEventRow.__table__.insert().values(
                    id=entry.id, audit_type=entry.audit_type, action=entry.action, resource_type=entry.resource_type,
                    resource_id=entry.resource_id, ticket_id=uuid.UUID(entry.ticket_id) if entry.ticket_id else None,
                    actor_type=entry.actor_type, actor_id=entry.actor_id, outcome=entry.outcome,
                    correlation_id=entry.correlation_id, causation_id=entry.causation_id, detail=entry.detail,
                    occurred_at=entry.occurred_at,
                )
            )
            session.commit()

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = select(AuditEventRow).order_by(AuditEventRow.occurred_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [
                AuditRecordEntry(
                    id=row.id, audit_type=row.audit_type, action=row.action, resource_type=row.resource_type,
                    resource_id=row.resource_id, ticket_id=str(row.ticket_id) if row.ticket_id else None,
                    actor_type=row.actor_type, actor_id=row.actor_id, outcome=row.outcome,
                    correlation_id=row.correlation_id, causation_id=row.causation_id, detail=row.detail, occurred_at=row.occurred_at,
                )
                for row in rows
            ]
