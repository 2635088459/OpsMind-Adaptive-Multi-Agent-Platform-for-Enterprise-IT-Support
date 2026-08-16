"""13-package-and-class-design §"Application Layer": IngestKnowledgeDocumentService, the
sole implementation of IngestKnowledgeDocumentUseCase. Drives
RECEIVED -> PARSED -> CHUNKED -> EMBEDDED -> INDEXED -> ACTIVE (03-state-machine),
falling to FAILED on any step's exception (10-failure-handling: "partial ingestion").
"""

from __future__ import annotations

import dataclasses
import hashlib

from memoryknowledge.application.commands import IngestKnowledgeDocumentCommand
from memoryknowledge.application.exceptions import DocumentAlreadyIngestedException, DocumentIngestionFailedException
from memoryknowledge.application.ports_out import (
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
from memoryknowledge.application.views import KnowledgeDocumentView
from memoryknowledge.domain.events import KnowledgeDocumentIndexed, KnowledgeGraphUpdated
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId, KnowledgeDocumentId
from memoryknowledge.domain.knowledge_document import KnowledgeDocument
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.values import SourceRef


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
        clock: ClockPort,
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

    def ingest(self, command: IngestKnowledgeDocumentCommand) -> KnowledgeDocumentView:
        if self._document_repository.find_by_natural_key(command.source_system, command.external_id, command.version) is not None:
            raise DocumentAlreadyIngestedException(command.source_system, command.external_id, command.version)

        now = self._clock.now()
        content_hash = hashlib.sha256(command.raw_content.encode()).hexdigest()
        document = KnowledgeDocument.receive(
            document_id=KnowledgeDocumentId.new_id(), source_system=command.source_system, external_id=command.external_id,
            title=command.title, document_type=command.document_type, acl=command.acl, version=command.version,
            content_hash=content_hash, created_at=now, effective_from=command.effective_from, expires_at=command.expires_at,
        )
        document = self._document_repository.save(document, expected_status=None)

        try:
            document = self._save_transition(document, document.mark_parsed())
            raw_chunks = self._document_parser_port.parse_and_chunk(command.raw_content, document.document_id, document.version)
            document = self._save_transition(document, document.mark_chunked())

            redacted_chunks = []
            for chunk in raw_chunks:
                redacted_text, _report = self._redaction_policy_port.redact(chunk.content)
                redacted_chunks.append(dataclasses.replace(
                    chunk, content=redacted_text, content_hash=hashlib.sha256(redacted_text.encode()).hexdigest(),
                ))

            embedded_chunks = []
            for chunk in redacted_chunks:
                embedding_ref, vector = self._embedding_provider.embed(chunk.content)
                self._embedding_repository.save(embedding_ref, vector)
                embedded_chunks.append(chunk.with_embedding(embedding_ref))
            self._document_repository.save_chunks(tuple(embedded_chunks))
            document = self._save_transition(document, document.mark_embedded())

            document = self._save_transition(document, document.mark_indexed())
            document = self._save_transition(document, document.activate())
        except Exception as exc:
            self._save_transition(document, document.mark_failed(str(exc)))
            raise DocumentIngestionFailedException(str(exc)) from exc

        self._outbox_repository.append(build_outbox_record(
            KnowledgeDocumentIndexed(document_id=document.document_id, version=document.version, chunk_count=len(embedded_chunks), occurred_at=now),
            "knowledge.document.indexed.v1", aggregate_id=str(document.document_id), occurred_at=now,
        ))

        node_count = edge_count = 0
        if command.extract_graph:
            source_refs = (SourceRef(source_type="document", source_id=str(document.document_id), version=document.version),)
            redacted_text = " ".join(c.content for c in embedded_chunks)
            entities, relations = self._entity_extractor_port.extract(redacted_text, source_refs)
            namespace = command.graph_namespace or command.source_system
            node_count, edge_count = self._upsert_graph(entities, relations, namespace, source_refs, now)
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

        return node_count, edge_count
