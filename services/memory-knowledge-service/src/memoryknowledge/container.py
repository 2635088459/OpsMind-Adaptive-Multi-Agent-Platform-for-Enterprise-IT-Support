"""Composition root. FastAPI has no classpath-scanning DI container the way Spring
does; this module is the one place allowed to import both memoryknowledge.infrastructure
and memoryknowledge.application, construct the singleton graph, and hand out
memoryknowledge.application.ports_in implementations to the interfaces layer via
FastAPI's Depends(). No business rule lives here — pure wiring, mirroring
agent-runtime-service's own container.py exactly (down to the `_build_memory_adapters`
seam SPEC-MK-002 will add a `_build_postgres_adapters` counterpart next to).
"""

from __future__ import annotations

from functools import lru_cache

from memoryknowledge.application.ports_in import (
    ExecuteRetentionUseCase,
    ExpandKnowledgeGraphUseCase,
    ExtractMemoryCandidateUseCase,
    IngestKnowledgeDocumentUseCase,
    OutboxDispatchPort,
    PublishMemoryUseCase,
    SearchMemoryUseCase,
    UpdateWorkingMemoryUseCase,
    ValidateMemoryCandidateUseCase,
)
from memoryknowledge.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from memoryknowledge.application.services.execute_retention import ExecuteRetentionService
from memoryknowledge.application.services.expand_knowledge_graph import ExpandKnowledgeGraphService
from memoryknowledge.application.services.extract_memory_candidate import ExtractMemoryCandidateService
from memoryknowledge.application.services.ingest_document import IngestKnowledgeDocumentService
from memoryknowledge.application.services.publish_memory import PublishMemoryService
from memoryknowledge.application.services.search_memory import SearchMemoryService
from memoryknowledge.application.services.update_working_memory import UpdateWorkingMemoryService
from memoryknowledge.application.services.validate_memory_candidate import ValidateMemoryCandidateService
from memoryknowledge.infrastructure.authorization import StaticAuthorizationPolicyAdapter
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.document_parser import SimpleDocumentParserAdapter
from memoryknowledge.infrastructure.embedding.embedding_provider import DeterministicHashEmbeddingProvider
from memoryknowledge.infrastructure.event_publisher import LoggingEventPublisherAdapter
from memoryknowledge.infrastructure.graph.entity_extractor import MarkerBasedEntityExtractorAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryCommandIdempotencyRepository,
    InMemoryEmbeddingRepository,
    InMemoryGraphEdgeRepository,
    InMemoryGraphNodeRepository,
    InMemoryKnowledgeDocumentRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryMemoryRepository,
    InMemoryOutboxRepository,
    InMemoryProcessedEventRepository,
    InMemoryRetrievalLogRepository,
    InMemoryWorkingMemoryRepository,
)
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter
from memoryknowledge.infrastructure.retrieval.reranker import SimpleGraphRerankerAdapter
from memoryknowledge.infrastructure.ticket_snapshot import NoOpTicketSnapshotPort
from memoryknowledge.infrastructure.workflow_trace import NoOpWorkflowTracePort
from memoryknowledge.settings import Settings, get_settings


class Container:
    def __init__(self, settings: Settings | None = None) -> None:
        settings = settings or get_settings()

        # SPEC-MK-002 will branch on settings.memory_persistence here the same way
        # agent-runtime-service's own container branches on agent_runtime_persistence —
        # only "memory" exists today.
        self.clock = SystemClockAdapter()
        self.working_memory_repository = InMemoryWorkingMemoryRepository()
        self.memory_candidate_repository = InMemoryMemoryCandidateRepository()
        self.memory_repository = InMemoryMemoryRepository()
        self.knowledge_document_repository = InMemoryKnowledgeDocumentRepository()
        self.embedding_repository = InMemoryEmbeddingRepository()
        self.retrieval_log_repository = InMemoryRetrievalLogRepository()
        self.graph_node_repository = InMemoryGraphNodeRepository()
        self.graph_edge_repository = InMemoryGraphEdgeRepository()
        self.processed_event_repository = InMemoryProcessedEventRepository()
        self.outbox_repository = InMemoryOutboxRepository()
        self.command_idempotency_repository = InMemoryCommandIdempotencyRepository()

        self.redaction_policy_port = RegexRedactionPolicyAdapter()
        self.document_parser_port = SimpleDocumentParserAdapter()
        self.embedding_provider = DeterministicHashEmbeddingProvider()
        self.entity_extractor_port = MarkerBasedEntityExtractorAdapter()
        self.graph_reranker_port = SimpleGraphRerankerAdapter()
        self.authorization_port = StaticAuthorizationPolicyAdapter()
        self.ticket_snapshot_port = NoOpTicketSnapshotPort()
        self.workflow_trace_port = NoOpWorkflowTracePort()
        # SPEC-MK-003 will branch on settings.event_publisher_adapter here — only
        # "logging" exists today.
        self.event_publisher_port = LoggingEventPublisherAdapter()

        self.update_working_memory_service = UpdateWorkingMemoryService(self.working_memory_repository, self.clock)
        self.search_memory_service = SearchMemoryService(
            self.memory_repository, self.knowledge_document_repository, self.retrieval_log_repository,
            self.authorization_port, self.graph_reranker_port, self.clock,
        )
        self.expand_knowledge_graph_service = ExpandKnowledgeGraphService(
            self.graph_node_repository, self.graph_edge_repository, self.authorization_port,
        )
        self.ingest_document_service = IngestKnowledgeDocumentService(
            self.knowledge_document_repository, self.document_parser_port, self.redaction_policy_port,
            self.embedding_provider, self.embedding_repository, self.entity_extractor_port,
            self.graph_node_repository, self.graph_edge_repository, self.outbox_repository, self.clock,
        )
        self.extract_memory_candidate_service = ExtractMemoryCandidateService(
            self.memory_candidate_repository, self.command_idempotency_repository, self.outbox_repository, self.clock,
        )
        self.validate_memory_candidate_service = ValidateMemoryCandidateService(
            self.memory_candidate_repository, self.memory_repository, self.redaction_policy_port, self.outbox_repository, self.clock,
        )
        self.publish_memory_service = PublishMemoryService(
            self.memory_candidate_repository, self.memory_repository, self.command_idempotency_repository, self.outbox_repository, self.clock,
        )
        self.execute_retention_service = ExecuteRetentionService(
            self.memory_repository, self.graph_node_repository, self.graph_edge_repository, self.authorization_port,
            self.command_idempotency_repository, self.outbox_repository, self.clock,
        )
        self.dispatch_outbox_events_service = DispatchOutboxEventsService(self.outbox_repository, self.event_publisher_port, self.clock)

        self.update_working_memory_port: UpdateWorkingMemoryUseCase = self.update_working_memory_service
        self.search_memory_port: SearchMemoryUseCase = self.search_memory_service
        self.expand_knowledge_graph_port: ExpandKnowledgeGraphUseCase = self.expand_knowledge_graph_service
        self.ingest_knowledge_document_port: IngestKnowledgeDocumentUseCase = self.ingest_document_service
        self.extract_memory_candidate_port: ExtractMemoryCandidateUseCase = self.extract_memory_candidate_service
        self.validate_memory_candidate_port: ValidateMemoryCandidateUseCase = self.validate_memory_candidate_service
        self.publish_memory_port: PublishMemoryUseCase = self.publish_memory_service
        self.execute_retention_port: ExecuteRetentionUseCase = self.execute_retention_service
        self.outbox_dispatch_port: OutboxDispatchPort = self.dispatch_outbox_events_service


@lru_cache(maxsize=1)
def get_container() -> Container:
    return Container()


def get_update_working_memory_port() -> UpdateWorkingMemoryUseCase:
    return get_container().update_working_memory_port


def get_search_memory_port() -> SearchMemoryUseCase:
    return get_container().search_memory_port


def get_expand_knowledge_graph_port() -> ExpandKnowledgeGraphUseCase:
    return get_container().expand_knowledge_graph_port


def get_ingest_knowledge_document_port() -> IngestKnowledgeDocumentUseCase:
    return get_container().ingest_knowledge_document_port


def get_extract_memory_candidate_port() -> ExtractMemoryCandidateUseCase:
    return get_container().extract_memory_candidate_port


def get_validate_memory_candidate_port() -> ValidateMemoryCandidateUseCase:
    return get_container().validate_memory_candidate_port


def get_publish_memory_port() -> PublishMemoryUseCase:
    return get_container().publish_memory_port


def get_execute_retention_port() -> ExecuteRetentionUseCase:
    return get_container().execute_retention_port


def get_outbox_dispatch_port() -> OutboxDispatchPort:
    return get_container().outbox_dispatch_port
