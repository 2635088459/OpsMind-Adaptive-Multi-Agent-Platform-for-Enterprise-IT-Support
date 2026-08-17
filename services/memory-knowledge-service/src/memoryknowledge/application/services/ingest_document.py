"""13-package-and-class-design §"Application Layer": IngestKnowledgeDocumentService, the
sole implementation of IngestKnowledgeDocumentUseCase. Drives
RECEIVED -> PARSED -> CHUNKED -> EMBEDDED -> INDEXED -> ACTIVE (03-state-machine),
falling to FAILED on any step's exception (10-failure-handling: "partial ingestion").
"""

from __future__ import annotations

import dataclasses
import hashlib

from opentelemetry import trace

from memoryknowledge.application.commands import IngestKnowledgeDocumentCommand, ReindexDocumentCommand, RetryDocumentIngestionCommand
from memoryknowledge.application.exceptions import (
    DocumentAlreadyIngestedException,
    DocumentIngestionFailedException,
    DocumentNotActiveException,
    KnowledgeDocumentNotFoundException,
)
from memoryknowledge.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    DocumentParserPort,
    EmbeddingProvider,
    EmbeddingRepository,
    EntityExtractorPort,
    GraphEdgeRepository,
    GraphNodeRepository,
    KnowledgeDocumentRepository,
    OutboxRepository,
    RedactionPolicyPort,
)
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.services.audit import AuditRecorder
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.application.views import KnowledgeDocumentView
from memoryknowledge.domain.events import KnowledgeDocumentIndexed, KnowledgeGraphUpdated
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId, KnowledgeDocumentId
from memoryknowledge.domain.knowledge_document import KnowledgeDocument
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.values import SourceRef

tracer = trace.get_tracer(__name__)


class IngestKnowledgeDocumentService:
    def __init__(
        self,
        document_repository: KnowledgeDocumentRepository,
        document_parser_port: DocumentParserPort,
        redaction_policy_port: RedactionPolicyPort,
        embedding_provider: EmbeddingProvider,
        embedding_repository: EmbeddingRepository,
        entity_extractor_port: EntityExtractorPort,
        graph_node_repository: GraphNodeRepository,
        graph_edge_repository: GraphEdgeRepository,
        outbox_repository: OutboxRepository,
        audit_record_repository: AuditRecordRepository,
        clock: ClockPort,
        telemetry: MemoryTelemetry,
    ) -> None:
        self._document_repository = document_repository
        self._document_parser_port = document_parser_port
        self._redaction_policy_port = redaction_policy_port
        self._embedding_provider = embedding_provider
        self._embedding_repository = embedding_repository
        self._entity_extractor_port = entity_extractor_port
        self._graph_node_repository = graph_node_repository
        self._graph_edge_repository = graph_edge_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def ingest(self, command: IngestKnowledgeDocumentCommand) -> KnowledgeDocumentView:
        """12-observability §"Traces" §"Ingestion trace spans" (parse / chunk /
        redaction scan / embedding / graph extraction / index write / outbox write) —
        one span for this whole pipeline, same granularity as SearchMemoryService.search()'s
        own "memory.search" span.
        """
        with tracer.start_as_current_span("knowledge.document.ingest"):
            return self._ingest_traced(command)

    def _ingest_traced(self, command: IngestKnowledgeDocumentCommand) -> KnowledgeDocumentView:
        content_hash = hashlib.sha256(command.raw_content.encode()).hexdigest()
        existing = self._document_repository.find_by_natural_key(command.source_system, command.external_id, command.version)
        if existing is not None:
            if existing.content_hash != content_hash:
                raise DocumentAlreadyIngestedException(command.source_system, command.external_id, command.version)
            # 09-concurrency-and-idempotency §"Document Reingestion": "相同 document
            # version 重复导入是幂等成功" — an identical (source_system, external_id,
            # version, content) retry replays the already-ingested result instead of
            # erroring, mirroring CommandIdempotencyGuard's own same-key-same-payload
            # replay semantics elsewhere in this service. Only a *different* content
            # hash under the same natural key is a genuine DOCUMENT_ALREADY_INGESTED
            # conflict.
            chunks = self._document_repository.find_chunks(existing.document_id)
            return KnowledgeDocumentView.from_domain(existing, chunk_count=len(chunks))

        document = KnowledgeDocument.receive(
            document_id=KnowledgeDocumentId.new_id(), source_system=command.source_system, external_id=command.external_id,
            title=command.title, document_type=command.document_type, acl=command.acl, version=command.version,
            content_hash=content_hash, created_at=self._clock.now(), classification=command.classification,
            effective_from=command.effective_from, expires_at=command.expires_at,
        )
        document = self._document_repository.save(document, expected_status=None)
        return self._run_pipeline(
            document, command.raw_content, command.extract_graph, command.graph_namespace, command.source_system,
            command.ingested_by, "ingest_document",
        )

    def retry(self, command: RetryDocumentIngestionCommand) -> KnowledgeDocumentView:
        """SPEC-MK-030 10-failure-handling §"Poison Document": "可由 admin 修正 metadata
        或 content 后重试." Re-runs the exact same pipeline ingest() uses, starting
        from the existing FAILED row transitioned back to RECEIVED — see
        KnowledgeDocument.retry()'s own docstring for why raw_content is always
        required here (never stored from the original failed attempt).
        """
        with tracer.start_as_current_span("knowledge.document.retry"):
            return self._retry_traced(command)

    def _retry_traced(self, command: RetryDocumentIngestionCommand) -> KnowledgeDocumentView:
        document = self._document_repository.find_by_id(command.document_id)
        if document is None:
            raise KnowledgeDocumentNotFoundException(command.document_id)

        previous_status = document.ingestion_status
        content_hash = hashlib.sha256(command.raw_content.encode()).hexdigest()
        document = document.retry(content_hash)
        document = self._document_repository.save(document, expected_status=previous_status.name)
        return self._run_pipeline(
            document, command.raw_content, command.extract_graph, command.graph_namespace, document.source_system,
            command.retried_by, "retry_document",
        )

    def reindex(self, command: ReindexDocumentCommand) -> KnowledgeDocumentView:
        """SPEC-MK-030 05-api-contracts §"Admin API": "reindex" — re-runs entity/
        relation extraction and graph upsert against an already-ACTIVE document's
        existing, already-redacted chunk content, without touching the chunks or
        their embeddings at all. Deliberately narrower than "re-embed everything":
        `DocumentChunkRow`'s own docstring ("CHUNKED 后 chunks 不可原地修改... always an
        insert of newly-created chunks, never an update") and
        `EmbeddingRepository.save()`'s own insert-only, no-op-on-an-already-known-
        vector_id contract both make chunk/embedding rows genuinely immutable once
        written in this codebase — reindexing embeddings in place would mean
        violating an existing, deliberate invariant to satisfy a vaguely-named LLD
        goal, which this session's own established discipline treats as worse than
        not building it. A real embedding-provider upgrade needing a fresh vector
        requires a full re-ingest under a new document version (SPEC-MK-002's own
        natural-key semantics), not a reindex. Recovering from an entity-extractor
        bug/rule-set upgrade — the concrete, buildable half of "reindex" — has no
        such conflict: GraphNode/GraphEdge upsert is already a real, idempotent,
        stable-key-deduped update-in-place operation elsewhere in this codebase.
        """
        with tracer.start_as_current_span("knowledge.document.reindex"):
            return self._reindex_traced(command)

    def _reindex_traced(self, command: ReindexDocumentCommand) -> KnowledgeDocumentView:
        document = self._document_repository.find_by_id(command.document_id)
        if document is None:
            raise KnowledgeDocumentNotFoundException(command.document_id)
        if document.ingestion_status.name != "ACTIVE":
            raise DocumentNotActiveException(command.document_id, document.ingestion_status.name)

        now = self._clock.now()
        chunks = self._document_repository.find_chunks(document.document_id)

        node_count = edge_count = 0
        if command.extract_graph:
            source_refs = (SourceRef(source_type="document", source_id=str(document.document_id), version=document.version),)
            redacted_text = " ".join(c.content for c in chunks)
            entities, relations = self._entity_extractor_port.extract(redacted_text, source_refs)
            namespace = command.graph_namespace or document.source_system
            node_count, edge_count = self._upsert_graph(entities, relations, namespace, source_refs, now)
            if node_count or edge_count:
                self._outbox_repository.append(build_outbox_record(
                    KnowledgeGraphUpdated(
                        graph_update_id=str(document.document_id), source_type="document", source_id=str(document.document_id),
                        node_count=node_count, edge_count=edge_count, index_version=document.version, occurred_at=now,
                    ),
                    "knowledge.graph.updated.v1", aggregate_id=str(document.document_id), occurred_at=now,
                ))

        self._audit_recorder.record(
            audit_type="MEMORY", action="reindex_document", resource_type="KNOWLEDGE_DOCUMENT", resource_id=str(document.document_id),
            outcome="SUCCESS", actor_id=command.requested_by, detail=f'{{"node_count": {node_count}, "edge_count": {edge_count}}}',
        )
        return KnowledgeDocumentView.from_domain(document, chunk_count=len(chunks))

    def _run_pipeline(
        self, document: KnowledgeDocument, raw_content: str, extract_graph: bool, graph_namespace: str | None,
        source_system: str, actor_id: str, audit_action: str,
    ) -> KnowledgeDocumentView:
        started_at = self._clock.now()
        embedded_chunks: list = []
        node_count = edge_count = 0
        try:
            document = self._save_transition(document, document.mark_parsed())
            raw_chunks = self._document_parser_port.parse_and_chunk(raw_content, document.document_id, document.version)
            if not raw_chunks:
                # 10-failure-handling §"Poison Document": "内容为空... document 状态进入
                # FAILED... 不生成 chunks / embeddings" — raw_content that is non-empty
                # at the wire (IngestDocumentRequest.raw_content requires min_length=1)
                # but parses to zero chunks (e.g. whitespace-only) must not silently
                # activate a document with no retrievable content.
                raise ValueError("document content produced no chunks (empty or unsupported content)")
            document = self._save_transition(document, document.mark_chunked())

            redacted_chunks = []
            for chunk in raw_chunks:
                redacted_text, _report = self._redaction_policy_port.redact(chunk.content)
                redacted_chunks.append(dataclasses.replace(
                    chunk, content=redacted_text, content_hash=hashlib.sha256(redacted_text.encode()).hexdigest(),
                ))

            for chunk in redacted_chunks:
                try:
                    embedding_ref, vector = self._embedding_provider.embed(chunk.content)
                except Exception:
                    self._telemetry.record_embedding_failure(audit_action)
                    raise
                self._embedding_repository.save(embedding_ref, vector)
                embedded_chunks.append(chunk.with_embedding(embedding_ref))
            self._document_repository.save_chunks(tuple(embedded_chunks))
            document = self._save_transition(document, document.mark_embedded())

            # UC-03 步骤 7 ("抽取 graph entities 和 graph edges") runs before step 8
            # ("index") — 08-transaction-and-outbox §"Graph Upsert Transaction": "如果
            # graph upsert 失败：document ingestion 不进入 ACTIVE" and 10-failure-handling
            # §"Graph Failure": "entity extraction 失败时，document / candidate 不进入
            # searchable active 状态" — both require this step to run inside the same
            # failure boundary as parse/chunk/embed, before mark_indexed/activate, not
            # after (a graph failure here must not leave an already-ACTIVE document
            # behind).
            now = self._clock.now()
            if extract_graph:
                source_refs = (SourceRef(source_type="document", source_id=str(document.document_id), version=document.version),)
                redacted_text = " ".join(c.content for c in embedded_chunks)
                entities, relations = self._entity_extractor_port.extract(redacted_text, source_refs)
                namespace = graph_namespace or source_system
                node_count, edge_count = self._upsert_graph(entities, relations, namespace, source_refs, now)

            document = self._save_transition(document, document.mark_indexed())
            document = self._save_transition(document, document.activate())
        except Exception as exc:
            self._save_transition(document, document.mark_failed(str(exc)))
            self._audit_recorder.record(
                audit_type="MEMORY", action=audit_action, resource_type="KNOWLEDGE_DOCUMENT", resource_id=str(document.document_id),
                outcome="FAILURE", actor_id=actor_id, detail=f'{{"reason": "{exc}"}}',
            )
            failed_latency_ms = int((self._clock.now() - started_at).total_seconds() * 1000)
            self._telemetry.record_document_ingestion_latency(failed_latency_ms, "failed")
            raise DocumentIngestionFailedException(str(exc)) from exc

        latency_ms = int((self._clock.now() - started_at).total_seconds() * 1000)
        self._telemetry.record_document_ingestion_latency(latency_ms, "success")
        self._outbox_repository.append(build_outbox_record(
            KnowledgeDocumentIndexed(document_id=document.document_id, version=document.version, chunk_count=len(embedded_chunks), occurred_at=now),
            "knowledge.document.indexed.v1", aggregate_id=str(document.document_id), occurred_at=now,
        ))
        self._audit_recorder.record(
            audit_type="MEMORY", action=audit_action, resource_type="KNOWLEDGE_DOCUMENT", resource_id=str(document.document_id),
            outcome="SUCCESS", actor_id=actor_id,
        )
        if node_count or edge_count:
            self._outbox_repository.append(build_outbox_record(
                KnowledgeGraphUpdated(
                    graph_update_id=str(document.document_id), source_type="document", source_id=str(document.document_id),
                    node_count=node_count, edge_count=edge_count, index_version=document.version, occurred_at=now,
                ),
                "knowledge.graph.updated.v1", aggregate_id=str(document.document_id), occurred_at=now,
            ))

        return KnowledgeDocumentView.from_domain(document, chunk_count=len(embedded_chunks))

    def _save_transition(self, previous: KnowledgeDocument, transitioned: KnowledgeDocument) -> KnowledgeDocument:
        return self._document_repository.save(transitioned, expected_status=previous.ingestion_status.name)

    def _upsert_graph(self, entities, relations, namespace: str, evidence_refs: tuple[SourceRef, ...], now) -> tuple[int, int]:
        """01-domain-model: "stableKey 用于避免重复实体" — reuses an existing node/edge by
        natural key rather than always inserting.
        """
        node_ids: dict[str, GraphNodeId] = {}
        node_count = 0
        for entity in entities:
            stable_key = f"{namespace}:{entity.node_type.name.lower()}:{entity.normalized_name}"
            existing = self._graph_node_repository.find_by_stable_key(stable_key, entity.node_type)
            if existing is None:
                node = GraphNode.create(
                    GraphNodeId.new_id(), entity.node_type, stable_key, entity.display_name, "INTERNAL",
                    entity.source_refs or evidence_refs, now,
                )
                self._graph_node_repository.save(node)
                node_ids[stable_key] = node.node_id
                node_count += 1
                self._telemetry.record_graph_node_created(entity.node_type.name)
            else:
                node_ids[stable_key] = existing.node_id

        edge_count = 0
        for relation in relations:
            from_key = f"{namespace}:{relation.from_entity.node_type.name.lower()}:{relation.from_entity.normalized_name}"
            to_key = f"{namespace}:{relation.to_entity.node_type.name.lower()}:{relation.to_entity.normalized_name}"
            from_id = node_ids.get(from_key)
            to_id = node_ids.get(to_key)
            if from_id is None or to_id is None:
                continue
            source_hash = hashlib.sha256(f"{from_key}|{to_key}|{relation.edge_type.name}".encode()).hexdigest()
            if self._graph_edge_repository.find_by_natural_key(from_id, to_id, relation.edge_type.name, source_hash) is not None:
                continue
            edge = GraphEdge.create(
                GraphEdgeId.new_id(), relation.edge_type, from_id, to_id, relation.confidence,
                relation.evidence_refs or evidence_refs, source_hash, now,
            )
            self._graph_edge_repository.save(edge)
            edge_count += 1
            self._telemetry.record_graph_edge_created(relation.edge_type.name)

        return node_count, edge_count
