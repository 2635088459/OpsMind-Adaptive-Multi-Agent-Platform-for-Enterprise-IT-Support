"""13-package-and-class-design §"Application Layer": SearchMemoryService, the sole
implementation of SearchMemoryUseCase. Implements the "Graph 如何使用" pipeline's
non-graph steps (01-domain-model): query normalization + access scope, seed scoring,
rerank, provenance, retrieval log — real seed-node graph expansion (step 3) is
phase-05 (retrieval-and-knowledge-graph); this spec's GraphRerankerPort call already
degrades gracefully to "no graph paths yet" rather than fabricating an explanation.
"""

from __future__ import annotations

import hashlib
import logging

from memoryknowledge.application.commands import SearchMemoryCommand
from memoryknowledge.application.ports_out import (
    AuthorizationPort,
    ClockPort,
    GraphRerankerPort,
    KnowledgeDocumentRepository,
    MemoryRepository,
    RetrievalLogRepository,
)
from memoryknowledge.application.views import SearchResultView
from memoryknowledge.domain.ids import RetrievalId
from memoryknowledge.domain.retrieval import RetrievalLog, score_text_relevance
from memoryknowledge.domain.values import Provenance, RetrievalResultItem

logger = logging.getLogger(__name__)

_DOCUMENT_CLASSIFICATION = "INTERNAL"
_MEMORY_CLASSIFICATION = "INTERNAL"
"""01-domain-model does not (yet) give Memory/MemoryVersion their own classification
field the way GraphNode has — a real per-memory classification is deferred to a later
phase spec (07-security-and-governance owns 11-security in depth). Treating every
result as this fixed, non-public classification keeps
02-business-invariants §"检索必须应用 ... classification ... 过滤" honestly enforced
(AuthorizationPort is still consulted for every result) rather than skipped outright.
"""


class SearchMemoryService:
    def __init__(
        self,
        memory_repository: MemoryRepository,
        knowledge_document_repository: KnowledgeDocumentRepository,
        retrieval_log_repository: RetrievalLogRepository,
        authorization_port: AuthorizationPort,
        graph_reranker_port: GraphRerankerPort,
        clock: ClockPort,
    ) -> None:
        self._memory_repository = memory_repository
        self._knowledge_document_repository = knowledge_document_repository
        self._retrieval_log_repository = retrieval_log_repository
        self._authorization_port = authorization_port
        self._graph_reranker_port = graph_reranker_port
        self._clock = clock

    def search(self, command: SearchMemoryCommand) -> SearchResultView:
        started_at = self._clock.now()
        query_hash = hashlib.sha256(command.query.encode()).hexdigest()
        degraded = False
        results: list[RetrievalResultItem] = []

        try:
            memory_type_names = tuple(t.name for t in command.memory_types)
            for version in self._memory_repository.find_active_versions_by_type(memory_type_names, limit=200):
                if not self._authorization_port.is_retrieval_authorized(command.access_scope, _MEMORY_CLASSIFICATION):
                    continue
                score = score_text_relevance(command.query, f"{version.summary} {version.content}", trust=version.source_trust_score)
                if score.combined <= 0:
                    continue
                results.append(RetrievalResultItem(
                    result_type="MEMORY", source_id=str(version.memory_id), source_version=version.version,
                    snippet=version.summary, score=score.combined,
                    provenance=Provenance(source_type="memory", source_ref=str(version.memory_version_id), redacted=True),
                ))

            for chunk in self._knowledge_document_repository.find_active_chunks(limit=200):
                document = self._knowledge_document_repository.find_by_id(chunk.document_id)
                if document is None:
                    continue
                if document.acl and command.access_scope.role not in document.acl:
                    continue
                if not self._authorization_port.is_retrieval_authorized(command.access_scope, _DOCUMENT_CLASSIFICATION):
                    continue
                score = score_text_relevance(command.query, chunk.content)
                if score.combined <= 0:
                    continue
                results.append(RetrievalResultItem(
                    result_type="DOCUMENT_CHUNK", source_id=str(chunk.document_id), source_version=chunk.document_version,
                    snippet=chunk.content, score=score.combined,
                    provenance=Provenance(source_type="document_chunk", source_ref=str(chunk.chunk_id), redacted=True),
                ))
        except Exception:
            # 10-failure-handling / 02-business-invariants §"退化模式": "不得因为检索不可用
            # 而伪造历史证据" — on any repository failure, degrade honestly to an empty (or
            # partial) result set rather than raising past the search boundary.
            logger.exception("search_memory degraded: repository access failed")
            degraded = True

        results.sort(key=lambda item: item.score, reverse=True)
        results = results[: command.max_results]
        if command.include_graph_paths:
            results = list(self._graph_reranker_port.rerank(tuple(results), ()))

        retrieval_id = RetrievalId.new_id()
        completed_at = self._clock.now()
        latency_ms = int((completed_at - started_at).total_seconds() * 1000)
        log = RetrievalLog.record(
            retrieval_id=retrieval_id, requester_type=command.requester_type, requester_id=command.requester_id,
            query_hash=query_hash, result_refs=tuple(r.provenance.source_ref for r in results), degraded=degraded,
            latency_ms=latency_ms, created_at=completed_at, ticket_id=command.ticket_id,
            ticket_cycle_id=command.ticket_cycle_id, workflow_instance_id=command.workflow_instance_id,
        )
        self._retrieval_log_repository.append(log)

        return SearchResultView(retrieval_id=retrieval_id, degraded=degraded, results=tuple(results))
