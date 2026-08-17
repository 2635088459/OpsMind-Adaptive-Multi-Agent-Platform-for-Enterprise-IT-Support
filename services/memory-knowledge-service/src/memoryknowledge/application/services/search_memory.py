"""13-package-and-class-design §"Application Layer": SearchMemoryService, the sole
implementation of SearchMemoryUseCase. Implements the "Graph 如何使用" pipeline
(01-domain-model) end to end: query redaction/normalization, seed scoring, bounded
graph expansion from those seeds, rerank, provenance, retrieval log.
"""

from __future__ import annotations

import dataclasses
import hashlib
import logging

from opentelemetry import trace

from memoryknowledge.application.commands import ExpandKnowledgeGraphCommand, SearchMemoryCommand
from memoryknowledge.application.ports_out import (
    AuthorizationPort,
    ClockPort,
    GraphNodeRepository,
    GraphRerankerPort,
    KnowledgeDocumentRepository,
    MemoryRepository,
    RedactionPolicyPort,
    RetrievalLogRepository,
)
from memoryknowledge.application.services.expand_knowledge_graph import ExpandKnowledgeGraphService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.application.views import SearchResultView
from memoryknowledge.domain.enums import GraphNodeType
from memoryknowledge.domain.ids import RetrievalId
from memoryknowledge.domain.retrieval import RetrievalLog, score_text_relevance
from memoryknowledge.domain.values import GraphPath, Provenance, RetrievalResultItem

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_FALLBACK_CLASSIFICATION = "INTERNAL"
"""SPEC-MK-025: Memory.classification/KnowledgeDocument.classification are now real,
caller-set fields (PublishMemoryService/IngestKnowledgeDocumentService both thread one
through) — this is only the fallback for the pathological case a Memory's own row
went missing between the version scan and this lookup (never true in practice; the
FK relationship makes it impossible), not a stand-in for a real value the way the
fixed constant this replaces was.
"""


class SearchMemoryService:
    def __init__(
        self,
        memory_repository: MemoryRepository,
        knowledge_document_repository: KnowledgeDocumentRepository,
        graph_node_repository: GraphNodeRepository,
        retrieval_log_repository: RetrievalLogRepository,
        authorization_port: AuthorizationPort,
        redaction_policy_port: RedactionPolicyPort,
        graph_reranker_port: GraphRerankerPort,
        expand_knowledge_graph_service: ExpandKnowledgeGraphService,
        clock: ClockPort,
        telemetry: MemoryTelemetry,
    ) -> None:
        self._memory_repository = memory_repository
        self._knowledge_document_repository = knowledge_document_repository
        self._graph_node_repository = graph_node_repository
        self._retrieval_log_repository = retrieval_log_repository
        self._authorization_port = authorization_port
        self._redaction_policy_port = redaction_policy_port
        self._graph_reranker_port = graph_reranker_port
        self._expand_knowledge_graph_service = expand_knowledge_graph_service
        self._clock = clock
        self._telemetry = telemetry

    def search(self, command: SearchMemoryCommand) -> SearchResultView:
        """12-observability §"Traces" §"Search trace spans" (request validation / access
        scope resolution / embedding query / vector search / keyword search / rerank /
        graph expansion / redaction / retrieval log write) — one span for this whole
        pipeline, mirroring agent-runtime-service's own SPEC-ARO-034 granularity
        (`ConsumeRuntimeEventService.consume()`'s single "event.consumed" span, not one
        child span per numbered sub-step no LLD text otherwise distinguishes).
        """
        with tracer.start_as_current_span("memory.search"):
            return self._search_traced(command)

    def _search_traced(self, command: SearchMemoryCommand) -> SearchResultView:
        self._telemetry.record_search_requested(command.requester_type)
        started_at = self._clock.now()
        # UC-02 step 2 "对 query 做 secret 检测和 normalization" — a query itself can
        # carry a pasted secret/PII (e.g. a copy-pasted error line); redacting before
        # it is hashed for query_hash/logged/scored keeps it out of both the
        # RetrievalLog and this service's own scoring path.
        query, _report = self._redaction_policy_port.redact(command.query)
        query_hash = hashlib.sha256(query.encode()).hexdigest()
        degraded = False
        degraded_reason: str | None = None
        results: list[RetrievalResultItem] = []

        try:
            memory_type_names = tuple(t.name for t in command.memory_types)
            for version in self._memory_repository.find_active_versions_by_type(memory_type_names, limit=200):
                memory = self._memory_repository.find_memory_by_id(version.memory_id)
                classification = memory.classification if memory is not None else _FALLBACK_CLASSIFICATION
                if not self._authorization_port.is_retrieval_authorized(command.access_scope, classification):
                    continue
                score = score_text_relevance(query, f"{version.summary} {version.content}", trust=version.source_trust_score)
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
                if not self._authorization_port.is_retrieval_authorized(command.access_scope, document.classification):
                    continue
                score = score_text_relevance(query, chunk.content)
                if score.combined <= 0:
                    continue
                results.append(RetrievalResultItem(
                    result_type="DOCUMENT_CHUNK", source_id=str(chunk.document_id), source_version=chunk.document_version,
                    snippet=chunk.content, score=score.combined,
                    provenance=Provenance(source_type="document_chunk", source_ref=str(chunk.chunk_id), redacted=True),
                ))
        except Exception:
            # 10-failure-handling §"Retrieval Degraded" / 02-business-invariants
            # §"退化模式": "不得因为检索不可用而伪造历史证据" — on any repository failure,
            # degrade honestly to an empty (or partial) result set rather than raising
            # past the search boundary.
            logger.exception("search_memory degraded: repository access failed")
            degraded = True
            degraded_reason = "REPOSITORY_UNAVAILABLE"

        results.sort(key=lambda item: item.score, reverse=True)
        results = results[: command.max_results]

        graph_degraded = False
        graph_paths: tuple[GraphPath, ...] = ()
        if command.include_graph_paths and results:
            # UC-02 step 4 "对 seed results 做 bounded graph expansion" — the MEMORY
            # graph node PublishMemoryService now creates per published MemoryVersion
            # (SPEC-MK-018) is the seed — that is where a SUPERSEDES edge (and any
            # future DERIVED_FROM/SUPPORTED_BY/RESOLVED_BY evidence edge UC-05 step 6
            # names) actually originates, not the more abstract MEMORY identity node.
            # DOCUMENT_CHUNK results have no graph node of their own yet (document
            # graph extraction only creates entity nodes it found in the content, not
            # one node per chunk), so only MEMORY results seed expansion.
            seed_node_id_by_source_id = {}
            for item in results:
                if item.result_type != "MEMORY":
                    continue
                node = self._graph_node_repository.find_by_stable_key(
                    f"memory_version:{item.provenance.source_ref}", GraphNodeType.MEMORY_VERSION,
                )
                if node is not None:
                    seed_node_id_by_source_id[item.source_id] = node.node_id

            if seed_node_id_by_source_id:
                try:
                    expansion = self._expand_knowledge_graph_service.expand(ExpandKnowledgeGraphCommand(
                        seed_node_ids=tuple(seed_node_id_by_source_id.values()), access_scope=command.access_scope,
                        requester_type=command.requester_type, requester_id=command.requester_id,
                        max_depth=command.max_graph_depth,
                    ))
                    graph_paths = expansion.paths
                except Exception:
                    # 10-failure-handling §"Graph Failure": "graph expansion 查询失败时，
                    # Search API 可返回 vector/keyword results，并标记 graphDegraded=true" —
                    # a graph-only failure must not degrade the whole search.
                    logger.exception("search_memory graph expansion degraded")
                    graph_degraded = True

            if graph_paths:
                results = list(self._graph_reranker_port.rerank(tuple(results), graph_paths))
                # 10-failure-handling: "graphDegraded=true 的结果必须降低排序权重" — the
                # inverse also holds here: a result whose own seed produced a path gets
                # that path attached for the caller's own provenance/explanation use.
                paths_by_seed: dict[str, list[GraphPath]] = {}
                for path in graph_paths:
                    paths_by_seed.setdefault(path.node_ids[0], []).append(path)
                node_id_by_source_id = {source_id: str(node_id) for source_id, node_id in seed_node_id_by_source_id.items()}
                results = [
                    dataclasses.replace(item, graph_paths=tuple(paths_by_seed.get(node_id_by_source_id.get(item.source_id, ""), ())))
                    for item in results
                ]

        retrieval_id = RetrievalId.new_id()
        completed_at = self._clock.now()
        latency_ms = int((completed_at - started_at).total_seconds() * 1000)
        self._telemetry.record_search_latency(latency_ms, command.requester_type)
        if degraded:
            self._telemetry.record_search_degraded(degraded_reason or "UNKNOWN")
        self._telemetry.record_retrieval_outcome(hit=bool(results))
        log = RetrievalLog.record(
            retrieval_id=retrieval_id, requester_type=command.requester_type, requester_id=command.requester_id,
            query_hash=query_hash, result_refs=tuple(r.provenance.source_ref for r in results), degraded=degraded,
            latency_ms=latency_ms, created_at=completed_at, ticket_id=command.ticket_id,
            ticket_cycle_id=command.ticket_cycle_id, workflow_instance_id=command.workflow_instance_id,
            graph_paths=graph_paths,
        )
        self._retrieval_log_repository.append(log)

        return SearchResultView(
            retrieval_id=retrieval_id, degraded=degraded, degraded_reason=degraded_reason, graph_degraded=graph_degraded,
            results=tuple(results),
        )
