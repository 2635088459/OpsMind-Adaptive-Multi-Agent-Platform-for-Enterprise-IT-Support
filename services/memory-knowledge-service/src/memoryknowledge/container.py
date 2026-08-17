"""Composition root. FastAPI has no classpath-scanning DI container the way Spring
does; this module is the one place allowed to import both memoryknowledge.infrastructure
and memoryknowledge.application, construct the singleton graph, and hand out
memoryknowledge.application.ports_in implementations to the interfaces layer via
FastAPI's Depends(). No business rule lives here — pure wiring, mirroring
agent-runtime-service's own container.py exactly.

SPEC-MK-002 introduces the real Postgres-backed adapters
(infrastructure.persistence.postgres) alongside SPEC-MK-001's in-memory ones.
memoryknowledge.settings.Settings.memory_persistence picks between them —
"postgres" (the default, for real runs) or "memory" (fast, hermetic tests set this
explicitly; unit tests that construct services directly, bypassing this container
entirely, are unaffected either way).
"""

from __future__ import annotations

from functools import lru_cache

from memoryknowledge.application.ports_in import (
    AuditRecordQueryPort,
    ExecuteRetentionUseCase,
    ExpandKnowledgeGraphUseCase,
    ExtractMemoryCandidateUseCase,
    IngestKnowledgeDocumentUseCase,
    OutboxDispatchPort,
    PublishMemoryUseCase,
    SearchMemoryUseCase,
    TicketMemorySourceEventConsumerPort,
    WorkflowMemorySourceEventConsumerPort,
    UpdateWorkingMemoryUseCase,
    ValidateMemoryCandidateUseCase,
    WorkingMemoryLifecycleUseCase,
    WorkingMemoryQueryUseCase,
)
from memoryknowledge.application.services.audit_query import AuditRecordQueryService
from memoryknowledge.application.services.consume_ticket_memory_source_event import ConsumeTicketMemorySourceEventService
from memoryknowledge.application.services.consume_workflow_memory_source_event import ConsumeWorkflowMemorySourceEventService
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
    InMemoryAuditRecordRepository,
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
from memoryknowledge.infrastructure.persistence.postgres.repositories import (
    PostgresAuditRecordRepository,
    PostgresCommandIdempotencyRepository,
    PostgresEmbeddingRepository,
    PostgresGraphEdgeRepository,
    PostgresGraphNodeRepository,
    PostgresKnowledgeDocumentRepository,
    PostgresMemoryCandidateRepository,
    PostgresMemoryRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresRetrievalLogRepository,
    PostgresWorkingMemoryRepository,
)
from memoryknowledge.infrastructure.persistence.postgres.session import build_engine, build_session_factory
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter
from memoryknowledge.infrastructure.retrieval.reranker import SimpleGraphRerankerAdapter
from memoryknowledge.infrastructure.ticket_snapshot import NoOpTicketSnapshotPort
from memoryknowledge.infrastructure.workflow_trace import NoOpWorkflowTracePort
from memoryknowledge.settings import Settings, get_settings


class _PersistenceAdapters:
    """Groups the twelve ports_out repositories so Container.__init__ stays a flat,
    readable list of services regardless of which backend built them.
    """

    def __init__(
        self,
        working_memory_repository,
        memory_candidate_repository,
        memory_repository,
        knowledge_document_repository,
        embedding_repository,
        retrieval_log_repository,
        graph_node_repository,
        graph_edge_repository,
        processed_event_repository,
        outbox_repository,
        command_idempotency_repository,
        audit_record_repository,
    ) -> None:
        self.working_memory_repository = working_memory_repository
        self.memory_candidate_repository = memory_candidate_repository
        self.memory_repository = memory_repository
        self.knowledge_document_repository = knowledge_document_repository
        self.embedding_repository = embedding_repository
        self.retrieval_log_repository = retrieval_log_repository
        self.graph_node_repository = graph_node_repository
        self.graph_edge_repository = graph_edge_repository
        self.processed_event_repository = processed_event_repository
        self.outbox_repository = outbox_repository
        self.command_idempotency_repository = command_idempotency_repository
        self.audit_record_repository = audit_record_repository


def _build_memory_adapters() -> _PersistenceAdapters:
    return _PersistenceAdapters(
        working_memory_repository=InMemoryWorkingMemoryRepository(),
        memory_candidate_repository=InMemoryMemoryCandidateRepository(),
        memory_repository=InMemoryMemoryRepository(),
        knowledge_document_repository=InMemoryKnowledgeDocumentRepository(),
        embedding_repository=InMemoryEmbeddingRepository(),
        retrieval_log_repository=InMemoryRetrievalLogRepository(),
        graph_node_repository=InMemoryGraphNodeRepository(),
        graph_edge_repository=InMemoryGraphEdgeRepository(),
        processed_event_repository=InMemoryProcessedEventRepository(),
        outbox_repository=InMemoryOutboxRepository(),
        command_idempotency_repository=InMemoryCommandIdempotencyRepository(),
        audit_record_repository=InMemoryAuditRecordRepository(),
    )


def _build_postgres_adapters(settings: Settings) -> _PersistenceAdapters:
    engine = build_engine(settings.sqlalchemy_url)
    session_factory = build_session_factory(engine)
    return _PersistenceAdapters(
        working_memory_repository=PostgresWorkingMemoryRepository(session_factory),
        memory_candidate_repository=PostgresMemoryCandidateRepository(session_factory),
        memory_repository=PostgresMemoryRepository(session_factory),
        knowledge_document_repository=PostgresKnowledgeDocumentRepository(session_factory),
        embedding_repository=PostgresEmbeddingRepository(session_factory),
        retrieval_log_repository=PostgresRetrievalLogRepository(session_factory),
        graph_node_repository=PostgresGraphNodeRepository(session_factory),
        graph_edge_repository=PostgresGraphEdgeRepository(session_factory),
        processed_event_repository=PostgresProcessedEventRepository(session_factory),
        outbox_repository=PostgresOutboxRepository(session_factory),
        command_idempotency_repository=PostgresCommandIdempotencyRepository(session_factory),
        audit_record_repository=PostgresAuditRecordRepository(session_factory),
    )


class Container:
    def __init__(self, settings: Settings | None = None) -> None:
        settings = settings or get_settings()
        adapters = _build_memory_adapters() if settings.memory_persistence == "memory" else _build_postgres_adapters(settings)

        self.clock = SystemClockAdapter()
        self.working_memory_repository = adapters.working_memory_repository
        self.memory_candidate_repository = adapters.memory_candidate_repository
        self.memory_repository = adapters.memory_repository
        self.knowledge_document_repository = adapters.knowledge_document_repository
        self.embedding_repository = adapters.embedding_repository
        self.retrieval_log_repository = adapters.retrieval_log_repository
        self.graph_node_repository = adapters.graph_node_repository
        self.graph_edge_repository = adapters.graph_edge_repository
        self.processed_event_repository = adapters.processed_event_repository
        self.outbox_repository = adapters.outbox_repository
        self.command_idempotency_repository = adapters.command_idempotency_repository
        self.audit_record_repository = adapters.audit_record_repository

        self.redaction_policy_port = RegexRedactionPolicyAdapter()
        self.document_parser_port = SimpleDocumentParserAdapter()
        self.embedding_provider = DeterministicHashEmbeddingProvider()
        self.entity_extractor_port = MarkerBasedEntityExtractorAdapter()
        self.graph_reranker_port = SimpleGraphRerankerAdapter()
        self.authorization_port = StaticAuthorizationPolicyAdapter()
        self.ticket_snapshot_port = NoOpTicketSnapshotPort()
        self.workflow_trace_port = NoOpWorkflowTracePort()
        # SPEC-MK-003 09-concurrency-and-idempotency / 12-observability: real RabbitMQ
        # publisher wiring stays deferred past this spec too (mirrors
        # agent-runtime-service's own SPEC-ARO-003 precedent, which likewise shipped
        # only the logging placeholder — real broker wiring landed at a later phase);
        # this spec's own job is the outbox row lifecycle (requeue/replay) and the
        # idempotency/audit baseline, both already backend-agnostic.
        self.event_publisher_port = LoggingEventPublisherAdapter()

        self.update_working_memory_service = UpdateWorkingMemoryService(
            self.working_memory_repository, self.redaction_policy_port, self.audit_record_repository, self.clock,
        )
        self.expand_knowledge_graph_service = ExpandKnowledgeGraphService(
            self.graph_node_repository, self.graph_edge_repository, self.authorization_port,
        )
        self.search_memory_service = SearchMemoryService(
            self.memory_repository, self.knowledge_document_repository, self.graph_node_repository,
            self.retrieval_log_repository, self.authorization_port, self.redaction_policy_port,
            self.graph_reranker_port, self.expand_knowledge_graph_service, self.clock,
        )
        self.ingest_document_service = IngestKnowledgeDocumentService(
            self.knowledge_document_repository, self.document_parser_port, self.redaction_policy_port,
            self.embedding_provider, self.embedding_repository, self.entity_extractor_port,
            self.graph_node_repository, self.graph_edge_repository, self.outbox_repository,
            self.audit_record_repository, self.clock,
        )
        self.extract_memory_candidate_service = ExtractMemoryCandidateService(
            self.memory_candidate_repository, self.command_idempotency_repository, self.outbox_repository,
            self.audit_record_repository, self.clock,
        )
        self.consume_ticket_memory_source_event_service = ConsumeTicketMemorySourceEventService(
            self.processed_event_repository, self.extract_memory_candidate_service, self.clock,
        )
        self.consume_workflow_memory_source_event_service = ConsumeWorkflowMemorySourceEventService(
            self.processed_event_repository, self.extract_memory_candidate_service, self.clock,
        )
        self.validate_memory_candidate_service = ValidateMemoryCandidateService(
            self.memory_candidate_repository, self.memory_repository, self.redaction_policy_port, self.outbox_repository,
            self.audit_record_repository, self.clock,
        )
        self.publish_memory_service = PublishMemoryService(
            self.memory_candidate_repository, self.memory_repository, self.graph_node_repository, self.graph_edge_repository,
            self.embedding_provider, self.embedding_repository, self.redaction_policy_port, self.command_idempotency_repository,
            self.outbox_repository, self.audit_record_repository, self.authorization_port, self.clock,
        )
        self.execute_retention_service = ExecuteRetentionService(
            self.memory_repository, self.graph_node_repository, self.graph_edge_repository, self.authorization_port,
            self.command_idempotency_repository, self.outbox_repository, self.audit_record_repository, self.clock,
        )
        self.dispatch_outbox_events_service = DispatchOutboxEventsService(self.outbox_repository, self.event_publisher_port, self.clock)
        self.audit_record_query_service = AuditRecordQueryService(self.audit_record_repository)

        self.update_working_memory_port: UpdateWorkingMemoryUseCase = self.update_working_memory_service
        self.working_memory_query_port: WorkingMemoryQueryUseCase = self.update_working_memory_service
        self.working_memory_lifecycle_port: WorkingMemoryLifecycleUseCase = self.update_working_memory_service
        self.search_memory_port: SearchMemoryUseCase = self.search_memory_service
        self.expand_knowledge_graph_port: ExpandKnowledgeGraphUseCase = self.expand_knowledge_graph_service
        self.ingest_knowledge_document_port: IngestKnowledgeDocumentUseCase = self.ingest_document_service
        self.extract_memory_candidate_port: ExtractMemoryCandidateUseCase = self.extract_memory_candidate_service
        self.ticket_memory_source_event_consumer_port: TicketMemorySourceEventConsumerPort = self.consume_ticket_memory_source_event_service
        self.workflow_memory_source_event_consumer_port: WorkflowMemorySourceEventConsumerPort = self.consume_workflow_memory_source_event_service
        self.validate_memory_candidate_port: ValidateMemoryCandidateUseCase = self.validate_memory_candidate_service
        self.publish_memory_port: PublishMemoryUseCase = self.publish_memory_service
        self.execute_retention_port: ExecuteRetentionUseCase = self.execute_retention_service
        self.outbox_dispatch_port: OutboxDispatchPort = self.dispatch_outbox_events_service
        self.audit_record_query_port: AuditRecordQueryPort = self.audit_record_query_service


@lru_cache(maxsize=1)
def get_container() -> Container:
    return Container()


def get_update_working_memory_port() -> UpdateWorkingMemoryUseCase:
    return get_container().update_working_memory_port


def get_working_memory_query_port() -> WorkingMemoryQueryUseCase:
    return get_container().working_memory_query_port


def get_working_memory_lifecycle_port() -> WorkingMemoryLifecycleUseCase:
    return get_container().working_memory_lifecycle_port


def get_search_memory_port() -> SearchMemoryUseCase:
    return get_container().search_memory_port


def get_expand_knowledge_graph_port() -> ExpandKnowledgeGraphUseCase:
    return get_container().expand_knowledge_graph_port


def get_ingest_knowledge_document_port() -> IngestKnowledgeDocumentUseCase:
    return get_container().ingest_knowledge_document_port


def get_extract_memory_candidate_port() -> ExtractMemoryCandidateUseCase:
    return get_container().extract_memory_candidate_port


def get_ticket_memory_source_event_consumer_port() -> TicketMemorySourceEventConsumerPort:
    return get_container().ticket_memory_source_event_consumer_port


def get_workflow_memory_source_event_consumer_port() -> WorkflowMemorySourceEventConsumerPort:
    return get_container().workflow_memory_source_event_consumer_port


def get_validate_memory_candidate_port() -> ValidateMemoryCandidateUseCase:
    return get_container().validate_memory_candidate_port


def get_publish_memory_port() -> PublishMemoryUseCase:
    return get_container().publish_memory_port


def get_execute_retention_port() -> ExecuteRetentionUseCase:
    return get_container().execute_retention_port


def get_outbox_dispatch_port() -> OutboxDispatchPort:
    return get_container().outbox_dispatch_port


def get_audit_record_query_port() -> AuditRecordQueryPort:
    return get_container().audit_record_query_port
